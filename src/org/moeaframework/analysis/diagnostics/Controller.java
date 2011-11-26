/* Copyright 2009-2011 David Hadka
 * 
 * This file is part of the MOEA Framework.
 * 
 * The MOEA Framework is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by 
 * the Free Software Foundation, either version 3 of the License, or (at your 
 * option) any later version.
 * 
 * The MOEA Framework is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY 
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public 
 * License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License 
 * along with the MOEA Framework.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.moeaframework.analysis.diagnostics;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.moeaframework.Analyzer;
import org.moeaframework.Instrumenter;
import org.moeaframework.analysis.collector.Accumulator;
import org.moeaframework.analysis.collector.InstrumentedAlgorithm;
import org.moeaframework.analysis.sensitivity.EpsilonHelper;
import org.moeaframework.core.Algorithm;
import org.moeaframework.core.EpsilonBoxDominanceArchive;
import org.moeaframework.core.NondominatedPopulation;
import org.moeaframework.core.Problem;
import org.moeaframework.core.Solution;
import org.moeaframework.core.spi.AlgorithmFactory;
import org.moeaframework.core.spi.ProblemFactory;
import org.moeaframework.util.Listeners;

/**
 * The controller manages the underlying data model, performs the evaluation
 * of jobs, and notifies any listeners when its state changes.
 */
public class Controller {
	
	/**
	 * The collection of listeners which are notified when the controller state
	 * changes.
	 */
	private final Listeners<ControllerListener> listeners;
	
	/**
	 * The collection of all results.
	 */
	private final Map<ResultKey, List<Accumulator>> accumulators;
	
	/**
	 * The last accumulator to be generated; or {@code null} if no last
	 * accumulator exists or has been previously cleared.
	 */
	private Accumulator lastAccumulator;
	
	/**
	 * {@code true} if the hypervolume indicator collector is included; 
	 * {@code false} otherwise.
	 */
	private boolean includeHypervolume = true;
	
	/**
	 * {@code true} if the generational distance indicator collector is
	 * included; {@code false} otherwise.
	 */
	private boolean includeGenerationalDistance = true;
	
	/**
	 * {@code true} if the inverted generational distance indicator collector is
	 * included; {@code false} otherwise.
	 */
	private boolean includeInvertedGenerationalDistance = true;
	
	/**
	 * {@code true} if the spacing indicator collector is included; 
	 * {@code false} otherwise.
	 */
	private boolean includeSpacing = true;
	
	/**
	 * {@code true} if the additive &epsilon;-indicator collector is
	 * included; {@code false} otherwise.
	 */
	private boolean includeAdditiveEpsilonIndicator = true;
	
	/**
	 * {@code true} if the contribution indicator collector is included; 
	 * {@code false} otherwise.
	 */
	private boolean includeContribution = true;
	
	/**
	 * {@code true} if the &epsilon;-progress collector is included; 
	 * {@code false} otherwise.
	 */
	private boolean includeEpsilonProgress = true;
	
	/**
	 * {@code true} if the adaptive multimethod variation collector is included;
	 * {@code false} otherwise.
	 */
	private boolean includeAdaptiveMultimethodVariation = true;
	
	/**
	 * {@code true} if the adaptive time continuation collector is included; 
	 * {@code false} otherwise.
	 */
	private boolean includeAdaptiveTimeContinuation = true;
	
	/**
	 * {@code true} if the elapsed time collector is included; {@code false} 
	 * otherwise.
	 */
	private boolean includeElapsedTime = true;
	
	/**
	 * {@code true} if the approximation set collector is included; 
	 * {@code false} otherwise.
	 */
	private boolean includeApproximationSet = true;
	
	/**
	 * {@code true} if the population size collector is included; {@code false}
	 * otherwise.
	 */
	private boolean includePopulationSize = true;
	
	/**
	 * The run progress of the current job being evaluated.
	 */
	private volatile int runProgress;
	
	/**
	 * The overall progress of the current job being evaluated.
	 */
	private volatile int overallProgress;
	
	/**
	 * {@code true} if the current job is to be canceled; {@code false} 
	 * otherwise.  The controller periodically checks this value to determine
	 * if it should interrupt the job.
	 */
	private volatile boolean canceled;
	
	/**
	 * The thread running the current job; or {@code null} if no job is
	 * running.
	 */
	private volatile Thread thread;
	
	/**
	 * The {@code DiagnosticTool} instance using this controller.
	 */
	private final DiagnosticTool frame;
	
	/**
	 * Constructs a new controller for the specified {@code DiagnosticTool}
	 * instance.
	 * 
	 * @param frame the {@code DiagnosticTool} instance using this controller
	 */
	public Controller(DiagnosticTool frame) {
		super();
		this.frame = frame;
		
		listeners = new Listeners<ControllerListener>();
		accumulators = new HashMap<ResultKey, List<Accumulator>>();
	}
	
	/**
	 * Adds the specified listener to receive all subsequent controller events.
	 * 
	 * @param listener the listener to receive controller events
	 */
	public void addControllerListener(ControllerListener listener) {
		listeners.add(listener);
	}
	
	/**
	 * Removes the specified listener so it no longer receives controller
	 * events.
	 * 
	 * @param listener the listener to no longer receive controller events
	 */
	public void removeControllerListener(ControllerListener listener) {
		listeners.remove(listener);
	}
	
	/**
	 * Fires a {@code MODEL_CHANGED} controller event.
	 */
	protected void fireModelChangedEvent() {
		fireEvent(new ControllerEvent(this, 
				ControllerEvent.Type.MODEL_CHANGED));
	}
	
	/**
	 * Fires a {@code STATE_CHANGED} controller event.
	 */
	protected void fireStateChangedEvent() {
		fireEvent(new ControllerEvent(this, 
				ControllerEvent.Type.STATE_CHANGED));
	}
	
	/**
	 * Fires a {@code PROGRESS_CHANGED} controller event.
	 */
	protected void fireProgressChangedEvent() {
		fireEvent(new ControllerEvent(this,
				ControllerEvent.Type.PROGRESS_CHANGED));
	}
	
	/**
	 * Fires a {@code VIEW_CHANGED} controller event.
	 */
	protected void fireViewChangedEvent() {
		fireEvent(new ControllerEvent(this,
				ControllerEvent.Type.VIEW_CHANGED));
	}
	
	/**
	 * Fires the specified controller event.  All listeners will receive this
	 * event on the event dispatch thread.
	 * 
	 * @param event the controller event to fire
	 */
	protected synchronized void fireEvent(final ControllerEvent event) {
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				for (ControllerListener listener : listeners) {
					listener.controllerStateChanged(event);
				}
			}
				
		});
	}
	
	/**
	 * Adds a new result to this controller.  If the specified key already
	 * exists, the accumulator is appended to the existing results.  A
	 * {@code MODEL_CHANGED} event is fired.
	 * 
	 * @param key the result key identifying the algorithm and problem
	 *        associated with these results
	 * @param accumulator the accumulator storing the results
	 */
	public void add(ResultKey key, Accumulator accumulator) {
		synchronized (accumulators) {
			if (!accumulators.containsKey(key)) {
				accumulators.put(key, new ArrayList<Accumulator>());
			}
			
			accumulators.get(key).add(accumulator);
			lastAccumulator = accumulator;
		}
		
		fireModelChangedEvent();
	}
	
	/**
	 * Adds a new result to this controller.  This method invokes
	 * {@link #add(ResultKey, Accumulator)}.
	 * 
	 * @param algorithm the algorithm associated with these results
	 * @param problem the problem associated with these results
	 * @param accumulator the accumulator storing the results
	 */
	public void add(String algorithm, String problem, Accumulator accumulator) {
		add(new ResultKey(algorithm, problem), accumulator);
	}
	
	/**
	 * Clears all results from this collector.  A {@code MODEL_CHANGED} event
	 * is fired.
	 */
	public void clear() {
		if (accumulators.isEmpty()) {
			return;
		}
		
		synchronized (accumulators) {
			accumulators.clear();
			lastAccumulator = null;
		}
		
		fireModelChangedEvent();
	}
	
	/**
	 * Returns an unmodifiable collection containing the results associated
	 * with the specified key.
	 * 
	 * @param key the result key
	 * @return an unmodifiable collection containing the results associated
	 *         with the specified key
	 */
	public List<Accumulator> get(ResultKey key) {
		synchronized (accumulators) {
			return Collections.unmodifiableList(accumulators.get(key));
		}
	}
	
	/**
	 * Returns an unmodifiable set of result keys contained in this controller.
	 * 
	 * @return an unmodifiable set of result keys contained in this controller
	 */
	public Set<ResultKey> getKeys() {
		synchronized (accumulators) {
			return Collections.unmodifiableSet(accumulators.keySet());
		}
	}
	
	/**
	 * Returns the last accumulator to be generated; or {@code null} if no last
	 * accumulator exists or has been previously cleared.
	 * 
	 * @return the last accumulator to be generated; or {@code null} if no last
	 *         accumulator exists or has been previously cleared
	 */
	public Accumulator getLastAccumulator() {
		synchronized (accumulators) {
			return lastAccumulator;
		}
	}
	
	/**
	 * Clears the last accumulator.  Subsequent invocations of
	 * {@link #getLastAccumulator()} will return {@code null} until a new
	 * accumulator is generated.
	 */
	public void clearLastAccumulator() {
		synchronized (accumulators) {
			lastAccumulator = null;
		}
	}
	
	/**
	 * Saves all results stored in this controller to the specified file.
	 * 
	 * @param file the file to which the results are saved
	 * @throws IOException if an I/O error occurred
	 */
	public void saveData(File file) throws IOException {
		synchronized (accumulators) {
			ObjectOutputStream oos = null;
			
			try {
				oos = new ObjectOutputStream(new FileOutputStream(file));
				oos.writeObject(accumulators);
			} finally {
				if (oos != null) {
					oos.close();
				}
			}
		}
	}
	
	/**
	 * Loads all results stored in the specified file.  A {@code MODEL_CHANGED}
	 * event is fired.
	 * 
	 * @param file the file containing the results to load
	 * @throws IOException if an I/O error occurred
	 */
	public void loadData(File file) throws IOException {
		ObjectInputStream ois = null;
		
		try {
			ois = new ObjectInputStream(new FileInputStream(file));
			
			Map<?, ?> data = (Map<?, ?>)ois.readObject();
			
			for (Map.Entry<?, ?> entry : data.entrySet()) {
				ResultKey key = (ResultKey)entry.getKey();
				List<?> list = (List<?>)entry.getValue();
				
				for (Object element : list) {
					add(key, (Accumulator)element);
				}
			}
		} catch (Exception e) {
			throw new IOException(e);
		} finally {
			if (ois != null) {
				ois.close();
			}
		}
	}
	
	/**
	 * Updates the progress of this controller.  A {@code PROGRESS_CHANGED}
	 * event is fired.
	 * 
	 * @param currentEvaluation the current evaluation number
	 * @param currentSeed the current seed number
	 * @param totalEvaluations the total number of evaluations
	 * @param totalSeeds the total number of seeds
	 */
	protected void updateProgress(int currentEvaluation, int currentSeed,
			int totalEvaluations, int totalSeeds) {
		runProgress = (int)(100*currentEvaluation/(double)totalEvaluations);
		overallProgress = (int)(100*currentSeed/(double)totalSeeds);
		
		fireProgressChangedEvent();
	}
	
	/**
	 * Creates and displays a dialog containing a statistical comparison of
	 * the selected results.
	 */
	public void showStatistics() {
		List<ResultKey> selectedResults = frame.getSelectedResults();
		String problemName = selectedResults.get(0).getProblem();
		Problem problem = null;
		
		try {
			problem = ProblemFactory.getInstance().getProblem(problemName);
			
			double epsilon = EpsilonHelper.getEpsilon(problem);
			
			Analyzer analyzer = new Analyzer()
					.withProblem(problemName)
					.withEpsilon(epsilon)
					.showAggregate()
					.showStatisticalSignificance();
			
			if (getIncludeHypervolume()) {
				analyzer.includeHypervolume();
			}
			
			if (getIncludeGenerationalDistance()) {
				analyzer.includeGenerationalDistance();
			}
			
			if (getIncludeInvertedGenerationalDistance()) {
				analyzer.includeInvertedGenerationalDistance();
			}
			
			if (getIncludeSpacing()) {
				analyzer.includeSpacing();
			}
			
			if (getIncludeAdditiveEpsilonIndicator()) {
				analyzer.includeAdditiveEpsilonIndicator();
			}
			
			if (getIncludeContribution()) {
				analyzer.includeContribution();
			}
			
			for (ResultKey key : selectedResults) {
				for (Accumulator accumulator : get(key)) {
					if (!accumulator.keySet().contains("Approximation Set")) {
						continue;
					}
					
					NondominatedPopulation population = 
							new EpsilonBoxDominanceArchive(epsilon);
					List<?> list = (List<?>)accumulator.get("Approximation Set",
							accumulator.size("Approximation Set")-1);
					
					for (Object object : list) {
						population.add((Solution)object);
					}
					
					analyzer.add(key.getAlgorithm(), population);
				}
			}
			
			ByteArrayOutputStream stream = new ByteArrayOutputStream();
			analyzer.printAnalysis(new PrintStream(stream));
			
			JFrame frame = new JFrame("Statistical Results");
			frame.setSize(800, 600);
			frame.setMinimumSize(new Dimension(800, 600));
			frame.setLocationRelativeTo(frame);
			frame.setIconImage(frame.getIconImage());
			frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
			frame.setLayout(new BorderLayout());
			
			JTextArea textArea = new JTextArea(stream.toString());
			textArea.setWrapStyleWord(true);
			
			frame.add(new JScrollPane(textArea), BorderLayout.CENTER);
			frame.setVisible(true);
		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
			if (problem != null) {
				problem.close();
			}
		}
	}
	
	/**
	 * Launches a thread to run the current evaluation job.
	 */
	public void run() {
		if (thread != null) {
			System.err.println("job already running");
			return;
		}
		
		final String problemName = frame.getProblem();
		final String algorithmName = frame.getAlgorithm();
		final int numberOfEvaluations = frame.getNumberOfEvaluations();
		final int numberOfSeeds = frame.getNumberOfSeeds();
		
		thread = new Thread() {
			
			public void run() {
				try {
					Problem problem = null;
					canceled = false;
					updateProgress(0, 0, numberOfEvaluations, numberOfSeeds);

					try {
						problem = ProblemFactory.getInstance().getProblem(
								problemName);
						
						Instrumenter instrumenter = new Instrumenter()
								.withFrequency(100)
								.withProblem(problemName)
								.withEpsilon(EpsilonHelper.getEpsilon(problem));
						
						if (getIncludeHypervolume()) {
							instrumenter.attachHypervolumeCollector();
						}
						
						if (getIncludeGenerationalDistance()) {
							instrumenter.attachGenerationalDistanceCollector();
						}
						
						if (getIncludeInvertedGenerationalDistance()) {
							instrumenter.attachInvertedGenerationalDistanceCollector();
						}
						
						if (getIncludeSpacing()) {
							instrumenter.attachSpacingCollector();
						}
						
						if (getIncludeAdditiveEpsilonIndicator()) {
							instrumenter.attachAdditiveEpsilonIndicatorCollector();
						}
						
						if (getIncludeContribution()) {
							instrumenter.attachContributionCollector();
						}
						
						if (getIncludeEpsilonProgress()) {
							instrumenter.attachEpsilonProgressCollector();
						}
						
						if (getIncludeAdaptiveMultimethodVariation()) {
							instrumenter.attachAdaptiveMultimethodVariationCollector();
						}
						
						if (getIncludeAdaptiveTimeContinuation()) {
							instrumenter.attachAdaptiveTimeContinuationCollector();
						}
					
						if (getIncludeElapsedTime()) {
							instrumenter.attachElapsedTimeCollector();
						}
						
						if (getIncludeApproximationSet()) {
							instrumenter.attachApproximationSetCollector();
						}
						
						if (getIncludePopulationSize()) {
							instrumenter.attachPopulationSizeCollector();
						}
	
						for (int i=0; i<numberOfSeeds; i++) {
							Properties properties = new Properties();
							properties.setProperty("maxEvaluations", 
									Integer.toString(numberOfEvaluations));
							
							Algorithm algorithm = null;
							
							try {
								algorithm = AlgorithmFactory.getInstance()
										.getAlgorithm(algorithmName, properties,
												problem);
							
								InstrumentedAlgorithm instrumentedAlgorithm = 
										instrumenter.instrument(algorithm);
								
								while (instrumentedAlgorithm.getNumberOfEvaluations() < numberOfEvaluations) {
									instrumentedAlgorithm.step();
									updateProgress(
											instrumentedAlgorithm.getNumberOfEvaluations(), 
											i, 
											numberOfEvaluations, 
											numberOfSeeds);
									
									if (canceled || Thread.currentThread().isInterrupted()) {
										return;
									}
								}
								
								add(algorithmName, problemName, 
										instrumentedAlgorithm.getAccumulator());
							} finally {
								if (algorithm != null) {
									algorithm.terminate();
								}
							}
							
							updateProgress(numberOfEvaluations, i+1, 
									numberOfEvaluations, numberOfSeeds);
							
							if (canceled || Thread.currentThread().isInterrupted()) {
								return;
							}
						}
					} finally {
						if (problem != null) {
							problem.close();
						}
					}
				} catch (Exception e) {
					handleException(e);
				} finally {
					canceled = false;
					thread = null;
					fireStateChangedEvent();
				}
			}
		};
		
		thread.setDaemon(true);
		thread.start();
		fireStateChangedEvent();
	}
	
	/**
	 * Notifies the controller that it should cancel the current evaluation job.
	 */
	public void cancel() {
		canceled = true;
	}
	
	/**
	 * Returns {@code true} if this controller is currently processing an
	 * evaluation job; {@code false} otherwise.
	 * 
	 * @return {@code true} if this controller is currently processing an
	 * evaluation job; {@code false} otherwise
	 */
	public boolean isRunning() {
		return thread != null;
	}

	/**
	 * Returns {@code true} if the hypervolume indicator collector is included;
	 * {@code false} otherwise.
	 * 
	 * @return {@code true} if the hypervolume indicator collector is included;
	 *         {@code false} otherwise
	 */
	public boolean getIncludeHypervolume() {
		return includeHypervolume;
	}

	/**
	 * Sets the inclusion of the hypervolume indicator collector.
	 * 
	 * @param includeHypervolume {@code true} if the hypervolume collector is
	 *        included; {@code false} otherwise
	 */
	public void setIncludeHypervolume(boolean includeHypervolume) {
		this.includeHypervolume = includeHypervolume;
	}

	/**
	 * Returns {@code true} if the generational distance indicator collector
	 * is included; {@code false} otherwise.
	 * 
	 * @return {@code true} if the generational distance indicator collector 
	 *         is included; {@code false} otherwise
	 */
	public boolean getIncludeGenerationalDistance() {
		return includeGenerationalDistance;
	}

	/**
	 * Sets the inclusion of the generational distance indicator collector.
	 * 
	 * @param includeGenerationalDistance {@code true} if the generational
	 *        distance indicator collector is included; {@code false} otherwise
	 */
	public void setIncludeGenerationalDistance(
			boolean includeGenerationalDistance) {
		this.includeGenerationalDistance = includeGenerationalDistance;
	}

	/**
	 * Returns {@code true} if the inverted generational distance indicator 
	 * collector is included; {@code false} otherwise.
	 * 
	 * @return {@code true} if the inverted generational distance indicator
	 *         collector is included; {@code false} otherwise
	 */
	public boolean getIncludeInvertedGenerationalDistance() {
		return includeInvertedGenerationalDistance;
	}

	/**
	 * Sets the inclusion of the inverted generational distance indicator 
	 * collector.
	 * 
	 * @param includeInvertedGenerationalDistance {@code true} if the inverted
	 *        generational distance indicator collector is included; 
	 *        {@code false} otherwise
	 */
	public void setIncludeInvertedGenerationalDistance(
			boolean includeInvertedGenerationalDistance) {
		this.includeInvertedGenerationalDistance = 
				includeInvertedGenerationalDistance;
	}

	/**
	 * Returns {@code true} if the spacing indicator collector is included;
	 * {@code false} otherwise.
	 * 
	 * @return {@code true} if the spacing indicator collector is included;
	 *         {@code false} otherwise
	 */
	public boolean getIncludeSpacing() {
		return includeSpacing;
	}

	/**
	 * Sets the inclusion of the spacing indicator collector.
	 * 
	 * @param includeSpacing {@code true} if the spacing indicator collector is
	 *        included; {@code false} otherwise
	 */
	public void setIncludeSpacing(boolean includeSpacing) {
		this.includeSpacing = includeSpacing;
	}

	/**
	 * Returns {@code true} if the additive &epsilon;-indicator collector is 
	 * included; {@code false} otherwise.
	 * 
	 * @return {@code true} if the additive &epsilon;-indicator collector is 
	 *         included; {@code false} otherwise
	 */
	public boolean getIncludeAdditiveEpsilonIndicator() {
		return includeAdditiveEpsilonIndicator;
	}

	/**
	 * Sets the inclusion of the additive &epsilon;-indicator collector.
	 * 
	 * @param includeAdditiveEpsilonIndicator {@code true} if the additive 
	 *        &epsilon;-indicator collector is included; {@code false} otherwise
	 */
	public void setIncludeAdditiveEpsilonIndicator(
			boolean includeAdditiveEpsilonIndicator) {
		this.includeAdditiveEpsilonIndicator = includeAdditiveEpsilonIndicator;
	}

	/**
	 * Returns {@code true} if the contribution indicator collector is included;
	 * {@code false} otherwise.
	 * 
	 * @return {@code true} if the contribution indicator collector is included;
	 *         {@code false} otherwise
	 */
	public boolean getIncludeContribution() {
		return includeContribution;
	}

	/**
	 * Sets the inclusion of the contribution indicator collector.
	 * 
	 * @param includeContribution {@code true} if the contribution indicator
	 *        collector is included; {@code false} otherwise
	 */
	public void setIncludeContribution(boolean includeContribution) {
		this.includeContribution = includeContribution;
	}

	/**
	 * Returns {@code true} if the &epsilon;-progress collector is included;
	 * {@code false} otherwise.
	 * 
	 * @return {@code true} if the &epsilon;-progress collector is included;
	 *         {@code false} otherwise
	 */
	public boolean getIncludeEpsilonProgress() {
		return includeEpsilonProgress;
	}

	/**
	 * Sets the inclusion of the &epsilon;-progress collector.
	 * 
	 * @param includeEpsilonProgress {@code true} if the &epsilon;-progress
	 *        collector is included; {@code false} otherwise
	 */
	public void setIncludeEpsilonProgress(boolean includeEpsilonProgress) {
		this.includeEpsilonProgress = includeEpsilonProgress;
	}

	/**
	 * Returns {@code true} if the adaptive multimethod variation collector is 
	 * included; {@code false} otherwise.
	 * 
	 * @return {@code true} if the adaptive multimethod variation collector is 
	 *         included; {@code false} otherwise
	 */
	public boolean getIncludeAdaptiveMultimethodVariation() {
		return includeAdaptiveMultimethodVariation;
	}

	/**
	 * Sets the inclusion of the adaptive multimethod variation collector.
	 * 
	 * @param includeAdaptiveMultimethodVariation {@code true} if the adaptive
	 *        multimethod variation collector is included; {@code false} 
	 *        otherwise
	 */
	public void setIncludeAdaptiveMultimethodVariation(
			boolean includeAdaptiveMultimethodVariation) {
		this.includeAdaptiveMultimethodVariation = 
				includeAdaptiveMultimethodVariation;
	}

	/**
	 * Returns {@code true} if the adaptive time continuation collector is 
	 * included; {@code false} otherwise.
	 * 
	 * @return {@code true} if the adaptive time continuation collector is 
	 *         included; {@code false} otherwise
	 */
	public boolean getIncludeAdaptiveTimeContinuation() {
		return includeAdaptiveTimeContinuation;
	}

	/**
	 * Sets the inclusion of the adaptive time continuation collector.
	 * 
	 * @param includeAdaptiveTimeContinuation {@code true} if the adaptive time
	 *        continuation collector is included; {@code false} otherwise
	 */
	public void setIncludeAdaptiveTimeContinuation(
			boolean includeAdaptiveTimeContinuation) {
		this.includeAdaptiveTimeContinuation = includeAdaptiveTimeContinuation;
	}

	/**
	 * Returns {@code true} if the elapsed time collector is included; 
	 * {@code false} otherwise.
	 * 
	 * @return {@code true} if the elapsed time collector is included; 
	 *         {@code false} otherwise
	 */
	public boolean getIncludeElapsedTime() {
		return includeElapsedTime;
	}

	/**
	 * Sets the inclusion of the elapsed time collector.
	 * 
	 * @param includeElapsedTime {@code true} if the elapsed time collector is 
	 *        included; {@code false} otherwise
	 */
	public void setIncludeElapsedTime(boolean includeElapsedTime) {
		this.includeElapsedTime = includeElapsedTime;
	}

	/**
	 * Returns {@code true} if the approximation set collector is included; 
	 * {@code false} otherwise.
	 * 
	 * @return {@code true} if the approximation set collector is included; 
	 *         {@code false} otherwise
	 */
	public boolean getIncludeApproximationSet() {
		return includeApproximationSet;
	}

	/**
	 * Sets the inclusion of the approximation set collector.
	 * 
	 * @param includeApproximationSet {@code true} if the approximation set 
	 *        collector is included; {@code false} otherwise
	 */
	public void setIncludeApproximationSet(boolean includeApproximationSet) {
		this.includeApproximationSet = includeApproximationSet;
	}

	/**
	 * Returns {@code true} if the population size collector is included; 
	 * {@code false} otherwise.
	 * 
	 * @return {@code true} if the population size collector is included; 
	 *         {@code false} otherwise
	 */
	public boolean getIncludePopulationSize() {
		return includePopulationSize;
	}

	/**
	 * Sets the inclusion of the population size collector.
	 * 
	 * @param includePopulationSize {@code true} if the population size 
	 *        collector is included; {@code false} otherwise
	 */
	public void setIncludePopulationSize(boolean includePopulationSize) {
		this.includePopulationSize = includePopulationSize;
	}
	
	/**
	 * Returns the run progress of the current job being evaluated.  The run
	 * progress measures the number of evaluations completed thus far.
	 * 
	 * @return the run progress of the current job being evaluated
	 */
	public int getRunProgress() {
		return runProgress;
	}

	/**
	 * Returns the overall progress of the current job being evaluated.  The
	 * overall progress measures the number of seeds completed thus far.
	 * 
	 * @return the overall progress of the current job being evaluated
	 */
	public int getOverallProgress() {
		return overallProgress;
	}

	/**
	 * Handles an exception, possibly displaying a dialog box containing details
	 * of the exception.
	 * 
	 * @param e the exception
	 */
	protected void handleException(Exception e) {
		e.printStackTrace();
		
		JOptionPane.showMessageDialog(
				frame, 
				e.getMessage() == null ? e.toString() : e.getMessage(), 
				"Error", 
				JOptionPane.ERROR_MESSAGE);
	}

}