/*
 * This file is part of drugis.org MTC.
 * MTC is distributed from http://drugis.org/mtc.
 * Copyright (C) 2009-2011 Gert van Valkenhoef.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.drugis.mtc.yadas;

import edu.uci.ics.jung.graph.util.Pair;
import gov.lanl.yadas.ArgumentMaker;
import gov.lanl.yadas.BasicMCMCBond;
import gov.lanl.yadas.Binomial;
import gov.lanl.yadas.ConstantArgument;
import gov.lanl.yadas.Gaussian;
import gov.lanl.yadas.GroupArgument;
import gov.lanl.yadas.IdentityArgument;
import gov.lanl.yadas.MCMCParameter;
import gov.lanl.yadas.MCMCUpdate;
import gov.lanl.yadas.Uniform;
import gov.lanl.yadas.UpdateTuner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math.random.JDKRandomGenerator;
import org.drugis.common.threading.AbstractIterativeComputation;
import org.drugis.common.threading.IterativeTask;
import org.drugis.common.threading.NullTask;
import org.drugis.common.threading.SimpleSuspendableTask;
import org.drugis.common.threading.Task;
import org.drugis.common.threading.TaskListener;
import org.drugis.common.threading.activity.ActivityModel;
import org.drugis.common.threading.activity.ActivityTask;
import org.drugis.common.threading.activity.DirectTransition;
import org.drugis.common.threading.activity.ForkTransition;
import org.drugis.common.threading.activity.JoinTransition;
import org.drugis.common.threading.activity.Transition;
import org.drugis.common.threading.event.TaskEvent;
import org.drugis.common.threading.event.TaskEvent.EventType;
import org.drugis.mtc.MCMCResults;
import org.drugis.mtc.MixedTreatmentComparison;
import org.drugis.mtc.Parameter;
import org.drugis.mtc.model.Measurement;
import org.drugis.mtc.model.Network;
import org.drugis.mtc.model.Study;
import org.drugis.mtc.model.Treatment;
import org.drugis.mtc.parameterization.BasicParameter;
import org.drugis.mtc.parameterization.ContinuousDataStartingValueGenerator;
import org.drugis.mtc.parameterization.DichotomousDataStartingValueGenerator;
import org.drugis.mtc.parameterization.InconsistencyParameter;
import org.drugis.mtc.parameterization.InconsistencyVariance;
import org.drugis.mtc.parameterization.NetworkModel;
import org.drugis.mtc.parameterization.NetworkParameter;
import org.drugis.mtc.parameterization.Parameterization;
import org.drugis.mtc.parameterization.PriorGenerator;
import org.drugis.mtc.parameterization.RandomEffectsVariance;
import org.drugis.mtc.parameterization.SplitParameter;
import org.drugis.mtc.parameterization.StartingValueGenerator;

abstract class YadasModel implements MixedTreatmentComparison {
	protected final Network d_network;
	protected Parameterization d_pmtz = null;
	
	private PriorGenerator d_priorGen;
	protected List<StartingValueGenerator> d_startGen = new ArrayList<StartingValueGenerator>();
	protected final int d_nChains = 4;
	
	private List<List<ParameterWriter>> d_writeList = new ArrayList<List<ParameterWriter>>();
	private List<List<MCMCUpdate>> d_updateList = new ArrayList<List<MCMCUpdate>>();

	protected Parameter d_randomEffectVar = new RandomEffectsVariance();
	protected Parameter d_inconsistencyVar = new InconsistencyVariance();

	private int d_burnInIter = 20000;
	protected int d_simulationIter = 100000;
	private int d_reportingInterval = 100;

	private YadasResults d_results = new YadasResults();
	private ActivityTask d_activityTask;

	private SimpleSuspendableTask d_finalPhase;

	private class BurnInChain extends AbstractIterativeComputation {
		private final int d_chain;
		
		public BurnInChain(int chain) {
			super(d_burnInIter);
			d_chain = chain;
		}
		
		public void doStep() {
			update(d_chain);
		}
	}

	private class SimulationChain extends AbstractIterativeComputation {
		private final int d_chain;
		
		public SimulationChain(int chain) {
			super(d_burnInIter);
			d_chain = chain;
		}
		
		public void doStep() {
			update(d_chain);
			output(d_chain);
		}
	}

	private class BurnInTask extends IterativeTask {
		public BurnInTask(int chain) {
			super(new BurnInChain(chain), "burn-in:" + chain);
			setReportingInterval(d_reportingInterval);
		}
	}
	
	private class SimulationTask extends IterativeTask {
		public SimulationTask(int chain) {
			super(new SimulationChain(chain), "simulation:" + chain);
			setReportingInterval(d_reportingInterval);
		}
	}

	public YadasModel(Network network) {
		d_network = network;

		// Create tasks for each phase of the MCMC simulation
		Task buildModelPhase = new SimpleSuspendableTask(new Runnable() {
			public void run() {
				buildModel();
			}
		}, "building model");
		List<Task> burnInPhase = new ArrayList<Task>(d_nChains);
		List<Task> simulationPhase = new ArrayList<Task>(d_nChains);
		for (int i = 0; i < d_nChains; ++i) {
			burnInPhase.add(new BurnInTask(i));
			simulationPhase.add(new SimulationTask(i));
		}
		d_finalPhase = new NullTask();

		// Build transition graph between phases of the MCMC simulation
		List<Transition> transitions = new ArrayList<Transition>();
		transitions.add(new ForkTransition(buildModelPhase, burnInPhase));
		transitions.add(new JoinTransition(simulationPhase, d_finalPhase));
		for (int i = 0; i < d_nChains; ++i) {
			transitions.add(new DirectTransition(burnInPhase.get(i), simulationPhase.get(i)));
		}
		
		// Together they form the full "activity"
		ActivityModel activityModel = new ActivityModel(buildModelPhase, d_finalPhase, transitions);
		d_activityTask = new ActivityTask(activityModel, "MCMC model");
	}

	abstract protected boolean isInconsistency();
	
	public boolean isReady() {
		return d_finalPhase.isFinished();
	}

	public ActivityTask getActivityTask() {
		return d_activityTask;
	}
	
	@Override
	public BasicParameter getRelativeEffect(Treatment base, Treatment subj) {
		return new BasicParameter(base, subj);
	}

	public int getBurnInIterations() {
		return d_burnInIter;
	}

	public void setBurnInIterations(int it) {
		validIt(it);
		d_burnInIter = it;
	}

	public int getSimulationIterations() {
		return d_simulationIter;
	}

	public void setSimulationIterations(int it) {
		validIt(it);
		d_simulationIter = it;
	}

	@Override
	public Parameter getRandomEffectsVariance() {
		return d_randomEffectVar;
	}
	
	public MCMCResults getResults() {
		return d_results;
	}

	private void validIt(int it) {
		if (it <= 0 || it % 100 != 0) {
			throw new IllegalArgumentException("Specified # iterations should be a positive multiple of 100");
		}
	}
	
	////
	//// Below: code to get starting values
	////
	
	private double getStartingSigma(StartingValueGenerator startVal) {
		return Math.random() * d_priorGen.getRandomEffectsSigma();
	}


	private double getStartingValue(StartingValueGenerator startVal, NetworkParameter p) {
		if (p instanceof BasicParameter) {
			return startVal.getRelativeEffect((BasicParameter) p);
		} else if (p instanceof SplitParameter) {
			SplitParameter sp = (SplitParameter) p;
			BasicParameter bp = new BasicParameter(sp.getBaseline(), sp.getSubject());
			return startVal.getRelativeEffect(bp);
		}
		return 0; // FIXME
	}
	
	////
	//// Below: code to initialize the MCMC model
	////

	protected abstract Parameterization buildNetworkModel();

	private void buildModel() {
		d_pmtz = buildNetworkModel();
		JDKRandomGenerator rng = new JDKRandomGenerator();
		double scale = 2.5;
		for (int i = 0; i < d_nChains; ++i) {
			switch (d_network.getType()) {
			case CONTINUOUS:
				d_startGen.add(new ContinuousDataStartingValueGenerator(d_network, rng, scale));
				break;
			case RATE:
				d_startGen.add(new DichotomousDataStartingValueGenerator(d_network, rng, scale));
				break;
			default:
				throw new IllegalArgumentException("Don't know how to generate starting values for " + d_network.getType() + " data");					
			}
		}
		
		d_priorGen = new PriorGenerator(d_network);

		List<Parameter> parameters = new ArrayList<Parameter>(d_pmtz.getParameters());
		parameters.add(d_randomEffectVar);
		if (isInconsistency()) {
			parameters.add(d_inconsistencyVar);
		}

		d_results.setDirectParameters(parameters);
		d_results.setNumberOfChains(d_nChains);
		d_results.setNumberOfIterations(d_simulationIter);

//		results.setDerivedParameters(
//			indirectParameters.map(p => (p, derivation(p))).toList)
		
		for (int i = 0 ; i < d_nChains; ++i) {
			createChain(i);
		}

		d_finalPhase.addTaskListener(new TaskListener() {
			@Override
			public void taskEvent(TaskEvent event) {
				if (event.getType() == EventType.TASK_FINISHED) {
					d_results.simulationFinished();
				}
			}
		});
	}
	
	////
	//// Below: code to create the structure of the MCMC model.
	////

	private void createChain(int chain) {
		StartingValueGenerator startVal = d_startGen.get(chain);

		// study baselines
		Map<Study, MCMCParameter> mu = new HashMap<Study, MCMCParameter>();
		for (Study s : d_network.getStudies()) {
			mu.put(s, new MCMCParameter(
					new double[] {startVal.getTreatmentEffect(s, d_pmtz.getStudyBaseline(s))},
					new double[] {0.1}, null));
		}
		// random effects
		Map<Study, MCMCParameter> delta = new HashMap<Study, MCMCParameter>();
		for (Study s : d_network.getStudies()) {
			double[] start = new double[reDim(s)];
			double[] step = new double[reDim(s)];
			Arrays.fill(step, 0.1);
			int i = 0;
			for (List<Pair<Treatment>> list : d_pmtz.parameterizeStudy(s)) {
				for (Pair<Treatment> pair: list) {
					start[i] = startVal.getRelativeEffect(s, getRelativeEffect(pair.getFirst(), pair.getSecond()));
					++i;
				}
			}
			delta.put(s, new MCMCParameter(start, step, null));
		}
		// basic parameters & inconsistency parameters
		List<NetworkParameter> parameters = d_pmtz.getParameters();
		double[] basicStart = new double[parameters.size()];
		double[] basicStep = new double[parameters.size()];
		Arrays.fill(basicStep, 0.1);
		for (int i = 0; i < parameters.size(); ++i) {
			basicStart[i] = getStartingValue(startVal, parameters.get(i));
		}
		MCMCParameter basic = new MCMCParameter(basicStart, basicStep, null);
		// variance
		MCMCParameter sigma = new MCMCParameter(
			new double[] {getStartingSigma(startVal)}, new double[] {0.1}, null);
		// inconsistency variance
		MCMCParameter sigmaw = isInconsistency() ? 
				new MCMCParameter(new double[] {getStartingSigma(startVal)}, new double[] {0.1}, null) : null;

		List<MCMCParameter> params = new ArrayList<MCMCParameter>();
		params.addAll(mu.values());
		params.addAll(delta.values());
		params.add(basic);
		params.add(sigma);
		if (isInconsistency()) {
			params.add(sigmaw);
		}

		// data bond
		switch (d_network.getType()) {
		case CONTINUOUS:
			continuousDataBond(mu, delta);
			break;
		case RATE:
			dichotomousDataBond(mu, delta);
			break;
		default:
			throw new IllegalArgumentException("Don't know how to handle " + d_network.getType() + " data");					
		}

		// random effects bound to basic/incons parameters
		for (Study study : d_network.getStudies()) {
			relativeEffectBond(study, delta.get(study), basic, sigma);
		}

		// per-study mean prior
		for (Study study : d_network.getStudies()) {
			new BasicMCMCBond(
					new MCMCParameter[] {mu.get(study)},
					new ArgumentMaker[] {
						new IdentityArgument(0),
						new ConstantArgument(0, 1),
						new ConstantArgument(Math.sqrt(d_priorGen.getVagueNormalVariance()), 1)
					},
					new Gaussian()
				);
		}

		// basic parameter prior
		int nBasic;
		for (nBasic = 0; nBasic < parameters.size() && !(parameters.get(nBasic) instanceof InconsistencyParameter); ++nBasic) {}
		int[] basicRange = new int[nBasic];
		for (int i = 0; i < nBasic; ++i) {
			basicRange[i] = i;
		}
		new BasicMCMCBond(
				new MCMCParameter[] {basic},
				new ArgumentMaker[] {
					new GroupArgument(0, basicRange), // FIXME: is this even allowed?
					new ConstantArgument(0, nBasic),
					new ConstantArgument(Math.sqrt(d_priorGen.getVagueNormalVariance()), nBasic)
				},
				new Gaussian()
			);

		// sigma prior
		new BasicMCMCBond(
				new MCMCParameter[] {sigma},
				new ArgumentMaker[] {
					new IdentityArgument(0),
					new ConstantArgument(0.00001),
					new ConstantArgument(d_priorGen.getRandomEffectsSigma())
				},
				new Uniform()
			);

		if (isInconsistency()) {
			int nIncons = parameters.size() - nBasic;
			int[] inconsRange = new int[nIncons];
			for (int i = 0; i < nIncons; ++i) {
				inconsRange[i] = nBasic + i;
			}
			// inconsistency prior
			new BasicMCMCBond(
					new MCMCParameter[] {basic, sigmaw},
					new ArgumentMaker[] {
						new GroupArgument(0, inconsRange),
						new ConstantArgument(0, nIncons),
						new GroupArgument(1, new int[nIncons])
					},
					new Gaussian()
				);

			// sigma_w prior
			new BasicMCMCBond(
					new MCMCParameter[] {sigmaw},
					new ArgumentMaker[] {
						new IdentityArgument(0),
						new ConstantArgument(0.00001),
						new ConstantArgument(d_priorGen.getInconsistencySigma())
					},
					new Uniform()
				);
		}
		
		List<MCMCUpdate> tuners = new ArrayList<MCMCUpdate>(params.size());
		for (MCMCParameter param : params) {
			tuners.add(new UpdateTuner(param, d_burnInIter / 50, 50, 1, Math.exp(-1)));
		}

		d_updateList.add(tuners);

		List<ParameterWriter> writers = new ArrayList<ParameterWriter>(params.size());
		for (int i = 0; i < parameters.size(); ++i) {
			writers.add(d_results.getParameterWriter(parameters.get(i), chain, basic, i));
		}
		writers.add(d_results.getParameterWriter(d_randomEffectVar, chain, sigma, 0));
		if (isInconsistency()) {
			writers.add(d_results.getParameterWriter(d_inconsistencyVar, chain, sigmaw, 0));
		}

		d_writeList.add(writers);
	}
	
	private void dichotomousDataBond(Map<Study, MCMCParameter> mu, Map<Study, MCMCParameter> delta) {
		// r_i ~ Binom(p_i, n_i) ; p_i = ilogit(theta_i) ;
		// theta_i = mu_s(i) + delta_s(i)b(i)t(i)
		
		for (Study study : d_network.getStudies()) {
			new BasicMCMCBond(
					new MCMCParameter[] {mu.get(study), delta.get(study)},
					new ArgumentMaker[] {
							new ConstantArgument(successArray(study)),
							new ConstantArgument(sampleSizeArray(study)),
							new SuccessProbabilityArgumentMaker(NetworkModel.getTreatments(study), d_pmtz.parameterizeStudy(study), 0, 1)
					},
					new Binomial()
				);
		}
	}

	private void continuousDataBond(Map<Study, MCMCParameter> mu, Map<Study, MCMCParameter> delta) {
		// m_i ~ N(theta_i, s_i) ;
		// theta_i = mu_s(i) + delta_s(i)b(i)t(i)
		
		for (Study study : d_network.getStudies()) {
			new BasicMCMCBond(
					new MCMCParameter[] {mu.get(study), delta.get(study)},
					new ArgumentMaker[] {
							new ConstantArgument(obsMeanArray(study)),
							new ThetaArgumentMaker(NetworkModel.getTreatments(study), d_pmtz.parameterizeStudy(study), 0, 1),
							new ConstantArgument(obsErrorArray(study))
					},
					new Gaussian()
				);
		}
	}

	private double[] successArray(Study study) {
		List<Treatment> treatments = NetworkModel.getTreatments(study);
		double[] arr = new double[treatments.size()];
		for (int i = 0; i < arr.length; ++i) {
			arr[i] = NetworkModel.findMeasurement(study, treatments.get(i)).getResponders();
		}
		return arr;
	}

	private double[] sampleSizeArray(Study study) {
		List<Treatment> treatments = NetworkModel.getTreatments(study);
		double[] arr = new double[treatments.size()];
		for (int i = 0; i < arr.length; ++i) {
			arr[i] = NetworkModel.findMeasurement(study, treatments.get(i)).getSampleSize();
		}
		return arr;
	}
	
	private double[] obsMeanArray(Study study) {
		List<Treatment> treatments = NetworkModel.getTreatments(study);
		double[] arr = new double[treatments.size()];
		for (int i = 0; i < arr.length; ++i) {
			arr[i] = NetworkModel.findMeasurement(study, treatments.get(i)).getMean();
		}
		return arr;
	}

	private double[] obsErrorArray(Study study) {
		List<Treatment> treatments = NetworkModel.getTreatments(study);
		double[] arr = new double[treatments.size()];
		for (int i = 0; i < arr.length; ++i) {
			final Measurement m = NetworkModel.findMeasurement(study, treatments.get(i));
			arr[i] = m.getStdDev() / Math.sqrt(m.getSampleSize());
		}
		return arr;
	}

	private void relativeEffectBond(Study study, MCMCParameter delta,
			MCMCParameter basic, MCMCParameter sigma) {
		ArgumentMaker[] arguments = new ArgumentMaker[2 + reDim(study)];
		arguments[0] = new IdentityArgument(0);
		arguments[1] = new RelativeEffectArgumentMaker(d_pmtz, study, 1, -1);

		if (reDim(study) == 1) {
			arguments[2] = new IdentityArgument(2);
			new BasicMCMCBond(
				new MCMCParameter[] {delta, basic, sigma},
				arguments,
				new Gaussian()
			);
		} else {
			List<ArgumentMaker> rows = SigmaRowArgumentMaker.createMatrixArgumentMaker(d_pmtz.parameterizeStudy(study), 2);
			for (int i = 0; i < rows.size(); ++i) {
				arguments[2 + i] = rows.get(i);
			}
			new BasicMCMCBond(
				new MCMCParameter[] {delta, basic, sigma},
				arguments,
				new MultivariateGaussian()
			);
		}
	}

	private int reDim(Study s) {
		return s.getTreatments().size() - 1;
	}

	private void update(int chain) {
		for (MCMCUpdate u : d_updateList.get(chain)) {
			try {
				u.update();
			} catch(Exception e) {
				throw new RuntimeException("Failed to update " + u, e);
			}
		}
	}

	protected void output(int chain) {
		for (ParameterWriter p : d_writeList.get(chain)) {
			p.output();
		}
	}
}