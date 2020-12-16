package org.opensha.sha.simulators.stiffness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.zip.ZipException;

import org.apache.commons.math3.stat.StatUtils;
import org.dom4j.DocumentException;
import org.opensha.commons.util.DataUtils;
import org.opensha.sha.faultSurface.FaultSection;
import org.opensha.sha.simulators.stiffness.AggregatedStiffnessCalculator.AggregationMethod;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.PatchAlignment;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.StiffnessDistribution;
import org.opensha.sha.simulators.stiffness.SubSectStiffnessCalculator.StiffnessType;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Doubles;

import scratch.UCERF3.FaultSystemRupSet;
import scratch.UCERF3.utils.FaultSystemIO;

/**
 * Stiffness (Coulomb) calculations are done between pairs of individual patches. Most faults are not
 * well represented by a single patch, so we instead divide them into many smaller patches and calculate
 * stiffness between patches. Ultimately, we want some aggregate measure of compatibility, such as the sum
 * of all interactions or the fraction that are positive. This class performs that aggregation in a flexible
 * manner consisting of layers of aggregation.
 * <br><br>
 * There are 4 possible layers of aggregation (places where aggregation occurs):
 * <ol>
 * <li>At the receiver patch level, across all source patches, for a given section-to-section pair</li>
 * <li>At the section-to-section level (1 source section, 1 receiver section, each with multiple patches)</li>
 * <li>At the sections-to-section level (N source sections, 1 receiver section, each with multiple patches)</li>
 * <li>At the sections-to-sections level (N source sections, M receiver sections, each with multiple patches)</li>
 * </ol>
 * You must specify the aggregation behavior at each of those levels, eache specified as an {@link AggregationMethod}.:
 * <br><ol>
 * <li>Process: regular single statistic from each distribution of values, e.g., sum, median, mean, fraction positive</li>
 * <li>Flatten: combine all distributions of values as input to this layer into a single (flattened) distribution,
 * from which a single statistic can be calculated if passed to a process layer</li>
 * <li>Passthrough: dont't do any aggregation at this layer, pass each full distribution onto the next layer</li>
 * <li>Receiver Sum: Flatten at the receiverID level (be that a section or patch ID), and then take the sum of that distribution
 * of values for that receiver</li>
 * </ol>
 * <br>
 * In practice, you will usually want to perform a process aggregation at either the first (receiver patch) or second
 * (receiver section) levels. For example, to get the median of all section-to-section patch interactions, the ordering
 * would be: Flatten -> Median. But if you wanted to instead get the fraction of passes which are net positive, you would
 * do: Sum -> Fact. Positive. You must always end with a process layer to compute a final statistic.
 * 
 * @author kevin
 *
 */
public class AggregatedStiffnessCalculator {
	
	private static final boolean D = false;

	private StiffnessType type;
	private SubSectStiffnessCalculator calc;
	private transient AggregatedStiffnessCache cache;
	
	public static final int MAX_LAYERS = 4;
	private AggregationMethod[] layers;
	private boolean allowSectToSelf = false;
	
	private static final int CACHE_ARRAY_SIZE;
	public static final int NUM_TERMINAL_METHODS;
	static {
		int maxTerminalIndex = -1;
		int numTerminal = 0;
		for (AggregationMethod method : AggregationMethod.values()) {
			if (method.isTerminal()) {
				maxTerminalIndex = Integer.max(maxTerminalIndex, method.ordinal());
				numTerminal++;
			}
		}
		CACHE_ARRAY_SIZE = maxTerminalIndex+1;
		NUM_TERMINAL_METHODS = numTerminal;
	}
	
	// multiplier to use for section IDs, to which patch IDs are added to get unique patch IDs
	private transient int patch_sect_id_multiplier = -1;
	private transient int patch_sect_id_add = -1;
	
	public static class StiffnessAggregation {
		
		private final double[] aggValues;
//		private final int numValues;
		
		public StiffnessAggregation(double[] values, int interactionCount) {
			Arrays.sort(values);
			this.aggValues = new double[CACHE_ARRAY_SIZE];
			int numPositive = 0;
			double sum = 0d;
			int count = values.length;
			for (double val : values) {
				sum += val;
				if (val >= 0)
					numPositive++;
			}
			aggValues[AggregationMethod.MEAN.ordinal()] = sum/(double)count;
			aggValues[AggregationMethod.MEDIAN.ordinal()] = DataUtils.median_sorted(values);
			aggValues[AggregationMethod.SUM.ordinal()] = sum;
			aggValues[AggregationMethod.MIN.ordinal()] = values[0];
			aggValues[AggregationMethod.MAX.ordinal()] = values[count-1];
			aggValues[AggregationMethod.FRACT_POSITIVE.ordinal()] = (double)numPositive/(double)count;
			aggValues[AggregationMethod.NUM_POSITIVE.ordinal()] = numPositive;
			aggValues[AggregationMethod.NUM_NEGATIVE.ordinal()] = count-numPositive;
			aggValues[AggregationMethod.GREATER_SUM_MEDIAN.ordinal()] = Math.max(get(AggregationMethod.SUM), get(AggregationMethod.MEDIAN));
			aggValues[AggregationMethod.GREATER_MEAN_MEDIAN.ordinal()] = Math.max(get(AggregationMethod.MEAN), get(AggregationMethod.MEDIAN));
			aggValues[AggregationMethod.COUNT.ordinal()] = interactionCount;
//			this.numValues = values.length;
		}
		
		StiffnessAggregation(AggregationMethod[] methods, double[] aggValues) {
			Preconditions.checkState(methods.length == aggValues.length);
			this.aggValues = new double[CACHE_ARRAY_SIZE];
			for (int i=0; i<aggValues.length; i++)
				this.aggValues[i] = Double.NaN;
			for (int i=0; i<methods.length; i++)
				this.aggValues[methods[i].ordinal()] = aggValues[i];
//			this.numValues
		}
		
		public double get(AggregationMethod aggMethod) {
			Preconditions.checkState(aggMethod.isTerminal(), "Can only cache values for terminal layers");
			return aggValues[aggMethod.ordinal()];
		}
	}
	
	public enum AggregationMethod {
		MEAN("Mean", true, true) {
			@Override
			public double calculate(double[] values) {
				return StatUtils.mean(values);
			}
		},
		MEDIAN("Median", true, true) {
			@Override
			public double calculate(double[] values) {
				Arrays.sort(values);
				return DataUtils.median_sorted(values);
			}
		},
		SUM("Sum", true, true) {
			@Override
			public double calculate(double[] values) {
				return StatUtils.sum(values);
			}
		},
		MIN("Minimum", true, true) {
			@Override
			public double calculate(double[] values) {
				return StatUtils.min(values);
			}
		},
		MAX("Maximum", true, true) {
			@Override
			public double calculate(double[] values) {
				return StatUtils.max(values);
			}
		},
		FRACT_POSITIVE("Fraction Positive", false, true) {
			@Override
			public double calculate(double[] values) {
				return NUM_POSITIVE.calculate(values)/values.length;
			}
		},
		NUM_POSITIVE("Num Positive", false, true) {
			@Override
			public double calculate(double[] values) {
				int count = 0;
				for (double val : values)
					if (val >= 0d)
						count++;
				return count;
			}
		},
		NUM_NEGATIVE("Num Negative", false, true) {
			@Override
			public double calculate(double[] values) {
				return values.length - NUM_POSITIVE.calculate(values);
			}
		},
		GREATER_SUM_MEDIAN("Max[Sum,Median]", true, true) {
			@Override
			public double calculate(double[] values) {
				return Math.max(SUM.calculate(values), MEDIAN.calculate(values));
			}
		},
		GREATER_MEAN_MEDIAN("Max[Mean,Median]", true, true) {
			@Override
			public double calculate(double[] values) {
				return Math.max(MEAN.calculate(values), MEDIAN.calculate(values));
			}
		},
		COUNT("Count", true, true) {
			@Override
			public double calculate(double[] values) {
				return values.length;
			}
		},
		FLATTEN("Flatten", true, false) {
			@Override
			public ReceiverDistribution[] aggregate(int higherLevelID, ReceiverDistribution[] dists) {
				return new ReceiverDistribution[] { flatten(higherLevelID, dists) };
			}
		},
		RECEIVER_SUM("ReceiverSum", true, false) {
			@Override
			public ReceiverDistribution[] aggregate(int higherLevelID, ReceiverDistribution[] dists) {
				Map<Integer, List<ReceiverDistribution>> grouped = Arrays.stream(dists).collect(
						Collectors.groupingBy(ReceiverDistribution::getReceiverID));
				ReceiverDistribution[] ret = new ReceiverDistribution[grouped.keySet().size()];
				int index = 0;
				for (Integer receiverID : grouped.keySet()) {
					List<ReceiverDistribution> receiverDists = grouped.get(receiverID);
					if (receiverDists.size() == 1) {
						ret[index++] = receiverDists.get(0);
					} else {
						ReceiverDistribution flattened = flatten(receiverID, receiverDists);
						ret[index++] = new ReceiverDistribution(receiverID,
								flattened.totNumInteractions, StatUtils.sum(flattened.values));
					}
				}
				return ret;
			}
		},
		NORM_BY_COUNT("Normalize By Interaction Count", true, false) {
			@Override
			public ReceiverDistribution[] aggregate(int higherLevelID, ReceiverDistribution[] dists) {
				double sum = 0d;
				int interactionCount = 0;
				for (ReceiverDistribution dist : dists) {
					interactionCount += dist.totNumInteractions;
					for (double val : dist.values)
						sum += val;
				}
				double fract = sum/(double)interactionCount;
				Preconditions.checkState(interactionCount > 0,
						"No interactions found at this level. Have %s dists and sum=%s", (Object)dists.length, sum);
				return new ReceiverDistribution[] { new ReceiverDistribution(higherLevelID, interactionCount, fract) };
			}
		},
		INTERACTION_SIGN("Interaction Sign", true, false) {
			@Override
			public ReceiverDistribution[] aggregate(int higherLevelID, ReceiverDistribution[] dists) {
				double sum = 0d;
				int interactionCount = 0;
				for (ReceiverDistribution dist : dists) {
					interactionCount += dist.totNumInteractions;
					for (double val : dist.values)
						sum += val;
				}
				double fract = sum/(double)interactionCount;
				Preconditions.checkState(interactionCount > 0,
						"No interactions found at this level. Have %s dists and sum=%s", (Object)dists.length, sum);
				if (fract >= 0.5)
					return new ReceiverDistribution[] { new ReceiverDistribution(higherLevelID, interactionCount, 1d) };
				return new ReceiverDistribution[] { new ReceiverDistribution(higherLevelID, interactionCount, -1d) };
			}
		},
		PASSTHROUGH("Passthrough", true, false) {
			@Override
			public ReceiverDistribution[] aggregate(int higherLevelID, ReceiverDistribution[] dists) {
				return dists;
			}
		};
		
		private String name;
		private boolean hasUnits;
		private boolean terminal;
		
		private AggregationMethod(String name, boolean hasUnits, boolean isTerminal) {
			this.name = name;
			this.hasUnits = hasUnits;
			this.terminal = isTerminal;
		}
		
		@Override
		public String toString() {
			return name;
		}
		
		/**
		 * Calculates the aggregated quantity for the given array of values. Must be a terminal layer.
		 * 
		 * @param values
		 * @return
		 */
		public double calculate(double[] values) {
			Preconditions.checkState(!terminal, "%s is terminal but hasn't implemented calculate(double[])", name());
			throw new IllegalStateException("Can't calculate a single value with a non-terminal aggregation method");
		};
		
		/**
		 * Aggregates the given distributions for processing by the next layer.
		 * 
		 * @param higherLevelID unique ID of the current aggregation level: receiver patch or receiver section, or
		 * -1 if this is the a multiple-receiver sections aggregation level (final layer)
		 * @param dists
		 * @return
		 */
		public ReceiverDistribution[] aggregate(int higherLevelID, ReceiverDistribution[] dists) {
			Preconditions.checkState(terminal, "Non-terminal aggreagation methods must override aggregate() method");
			ReceiverDistribution[] aggregated = new ReceiverDistribution[dists.length];
			int index = 0;
			for (ReceiverDistribution dist : dists)
				aggregated[index++] = new ReceiverDistribution(dist.receiverID, dist.totNumInteractions, calculate(dist.values));
			return aggregated;
		}
		
		/**
		 * Gets a single value from the given distributions. Must be a terminal layer.
		 * 
		 * @param dists
		 * @return single aggregated value
		 * @throws IllegalStateException if this is not a terminal layer
		 */
		public double get(ReceiverDistribution[] dists) {
			Preconditions.checkState(terminal, "Can't get a single value for a non-terminal aggregation method");
			ReceiverDistribution dist;
			if (dists.length > 1) {
				// flatten it for final processing
				dist = flatten(-1, dists);
			} else {
				Preconditions.checkState(dists.length == 1, "No distributions left at this layer");
				dist = dists[0];
			}
			return calculate(dist.values);
		}
		
		/**
		 * 
		 * @return true if this a terminal layer that can supply a single aggregated value, false if it is a layer that organizes or
		 * transforms higher level distributions
		 */
		public boolean isTerminal() {
			return terminal;
		}
		
		public boolean hasUnits() {
			return hasUnits;
		}
		
	}
	
	public static class ReceiverDistribution {
		public final int receiverID;
		public final double[] values;
		public final int totNumInteractions;
		
		public ReceiverDistribution(int receiverID, int totNumInteractions, List<Double> values) {
			this(receiverID, totNumInteractions, Doubles.toArray(values));
		}
		
		public ReceiverDistribution(int receiverID, int totNumInteractions, double value) {
			this(receiverID, totNumInteractions, new double[] { value });
		}
		
		public ReceiverDistribution(int receiverID, int totNumInteractions, double[] values) {
			this.receiverID = receiverID;
			this.totNumInteractions = totNumInteractions;
			this.values = values;
		}
		
		public int getReceiverID() {
			return receiverID;
		}
		
		@Override
		public String toString() {
			return receiverID+": "+DoubleStream.of(values).mapToObj(String::valueOf).collect(Collectors.joining(","))
					+"\ttotNumInteractions="+totNumInteractions;
		}
	}
	
	private static ReceiverDistribution flatten(int receiverID, ReceiverDistribution[] dists) {
		Preconditions.checkState(dists.length > 0);
		double[] flattened;
		int totNumInteractions = 0;
		if (dists.length == 1) {
			flattened = dists[0].values;
			totNumInteractions = dists[0].totNumInteractions;
		} else {
			int totSize = 0;
			for (ReceiverDistribution dist : dists)
				totSize += dist.values.length;
			flattened = new double[totSize];
			int index = 0;
			for (ReceiverDistribution dist : dists) {
				System.arraycopy(dist.values, 0, flattened, index, dist.values.length);
				index += dist.values.length;
				totNumInteractions += dist.totNumInteractions;
			}
		}
		return new ReceiverDistribution(receiverID, totNumInteractions, flattened);
	}
	
	private static ReceiverDistribution flatten(int receiverID, List<ReceiverDistribution> dists) {
		Preconditions.checkState(dists.size() > 0);
		double[] flattened;
		int totNumInteractions = 0;
		if (dists.size() == 1) {
			flattened = dists.get(0).values;
			totNumInteractions = dists.get(0).totNumInteractions;
		} else {
			int totSize = 0;
			for (ReceiverDistribution dist : dists)
				totSize += dist.values.length;
			flattened = new double[totSize];
			int index = 0;
			for (ReceiverDistribution dist : dists) {
				System.arraycopy(dist.values, 0, flattened, index, dist.values.length);
				index += dist.values.length;
				totNumInteractions += dist.totNumInteractions;
			}
		}
		return new ReceiverDistribution(receiverID, totNumInteractions, flattened);
	}
	
//	/**
//	 * Convenience method to get an aggregated calculator that uses the median patch interaction for sect-to-sect,
//	 * and sums those medians across multiple sects (sects-to-sect or sects-to-sects).
//	 * 
//	 * @param type
//	 * @param calc
//	 * @return
//	 */
//	public static AggregatedStiffnessCalculator buildMedianPatchSumSects(StiffnessType type, SubSectStiffnessCalculator calc) {
//		return builder(type, calc).sectToSectAgg(AggregationMethod.MEDIAN).sectsToSectsAgg(AggregationMethod.SUM).get();
//	}
//	
	public static Builder builder(StiffnessType type, SubSectStiffnessCalculator calc) {
		return new Builder(type, calc);
	}
	
	public static class Builder {
		
		private StiffnessType type;
		private SubSectStiffnessCalculator calc;
		private List<AggregationMethod> layers;
		private boolean allowSectToSelf = false;

		private Builder(StiffnessType type, SubSectStiffnessCalculator calc) {
			this.type = type;
			this.calc = calc;
			this.layers = new ArrayList<>();
		}
		
		/**
		 * 
		 * @param allowSectToSelf if true, calculations between the same source and receiver section will be included.
		 * The calculation between the exact same source and receiver patch will always be excluded. 
		 * @return
		 */
		public Builder allowSectToSelf(boolean allowSectToSelf) {
			this.allowSectToSelf = allowSectToSelf;
			return this;
		}
		
		public Builder flatten() {
			layers.add(AggregationMethod.FLATTEN);
			return this;
		}
		
		public Builder receiverSum() {
			layers.add(AggregationMethod.RECEIVER_SUM);
			return this;
		}
		
		public Builder process(AggregationMethod method) {
			layers.add(method);
			return this;
		}
		
		public Builder passthrough() {
			layers.add(AggregationMethod.PASSTHROUGH);
			return this;
		}
		
		/**
		 * Aggregate section-to-section patch interactions at the receiver patch level first using the given
		 * aggregation method.
		 * 
		 * @param aggMethod
		 * @return
		 */
		public Builder receiverPatchAgg(AggregationMethod aggMethod) {
			Preconditions.checkState(layers.isEmpty(), "Receiver patch aggregation must be specified first");
			process(aggMethod);
			return this;
		}
		
		/**
		 * Aggregate method for section-to-section patch interactions. Receiver patch distributions will be flattened
		 * if no receiver path aggregation layer has been supplied.
		 * 
		 * @param aggMethod
		 * @return
		 */
		public Builder sectToSectAgg(AggregationMethod aggMethod) {
			Preconditions.checkState(layers.size() < 2);
			if (layers.isEmpty())
				flatten();
			process(aggMethod);
			return this;
		}
		
		/**
		 * Aggregate method for multiple source sections to a single receiver section. Must have already supplied a
		 * section-section aggregation level (via the sectToSectAggg(AggregationMethod) method or manual layer specification).
		 * 
		 * @param aggMethod
		 * @return
		 */
		public Builder sectsToSectAgg(AggregationMethod aggMethod) {
			Preconditions.checkState(layers.size() == 2, "Must have supplied a sect-to-sect aggregation level (and nothing further)");
			process(aggMethod);
			return this;
		}
		
		/**
		 * Aggregate method for multiple source sections to a multiple receiver sections. If a sections-to-section
		 * aggregation method is supplied, it will be applied first, otherwise a flatten layer will be applied first.
		 * 
		 * @param aggMethod
		 * @return
		 */
		public Builder sectsToSectsAgg(AggregationMethod aggMethod) {
			Preconditions.checkState(layers.size() >= 2, "Must have supplied at least a sect-to-sect aggregation level first");
			Preconditions.checkState(layers.size() < 4, "Aggregation levels already completely specified");
			if (layers.size() == 2)
				flatten(); // flatten at sects-to-sect first
			process(aggMethod);
			return this;
		}
		
		public AggregatedStiffnessCalculator get() {
			return new AggregatedStiffnessCalculator(type, calc, allowSectToSelf, this.layers.toArray(new AggregationMethod[0]));
		}
	}
	
	/**
	 * 
	 * @param type stiffness type that we are calculating
	 * @param calc stiffness calculator between two subsections
	 * @param allowSectToSelf if true, calculations between the same source and receiver section will be included.
		 * The calculation between the exact same source and receiver patch will always be excluded.
	 * @param layers aggregations layers. Must supply at least 1, and the final layer must be a terminal layer.
	 */
	public AggregatedStiffnessCalculator(StiffnessType type, SubSectStiffnessCalculator calc, boolean allowSectToSelf,
			AggregationMethod... layers) {
		super();
		this.type = type;
		this.calc = calc;
		this.allowSectToSelf = allowSectToSelf;
		Preconditions.checkState(layers.length > 0, "must supply at least 1 aggregation layer");
		Preconditions.checkState(layers.length < 5, "only 4 aggregation layers are possible");
		for (int i=0; i<layers.length; i++)
			Preconditions.checkNotNull(layers[i], "Layer at level %s is null", i);
		Preconditions.checkState(layers[layers.length-1].isTerminal(),
				"Final layer must be a terminal layer (but is %s)", layers[layers.length-1]);
		this.layers = layers;
	}
	
	private int uniquePatchID(int sectID, int patchID) {
		if (patch_sect_id_multiplier <= 0) {
			int numSects = calc.getSubSects().size();
			patch_sect_id_add = numSects;
			patch_sect_id_multiplier = Integer.MAX_VALUE / numSects;
//			System.out.println("MULTIPLIER="+patch_sect_id_multiplier+" for "+numSects+" sects");
		}
		Preconditions.checkState(patchID < patch_sect_id_multiplier-1,
				"Patch ID overflow: patchID=%s, multiplier=%s", patchID, patch_sect_id_multiplier);
		return patch_sect_id_add + sectID*patch_sect_id_multiplier + patchID;
	}
	
	private ReceiverDistribution[] aggRecieverPatches(FaultSection source, FaultSection receiver) {
		Preconditions.checkState(layers.length > 0, "Patch aggregation layer not supplied");
		int sourceID = source.getSectionId();
		int receiverID = receiver.getSectionId();
		Preconditions.checkState(allowSectToSelf || sourceID != receiverID, "srouceID=receiverID and allowSectToSelf is false");
		
		if (layers[0].isTerminal()) {
			// we can cache at this level
			
			if (cache == null)
				cache = calc.getAggregationCache(type);
			
			ReceiverDistribution[] cached = cache.getPatchAggregated(layers[0], source, receiver);
			if (cached != null) {
				if (D) {
					System.out.println(source.getSectionId()+" -> "+receiver.getSectionId()+", patchAgg="+layers[0]+"\n\tCACHED:");
					for (ReceiverDistribution dist : cached)
						System.out.println("\t"+dist);
				}
				return cached;
			}
		}
		
		StiffnessDistribution dist = calc.calcStiffnessDistribution(source, receiver);
		double[][] values = dist.get(type);
		double[] receiverPatchVals = new double[values.length];
		
		ReceiverDistribution[] receiverDists = new ReceiverDistribution[values.length];
		boolean sameSect = sourceID == receiverID;
		for (int r=0; r<receiverPatchVals.length; r++) {
			double[] receiverVals;
			if (sameSect) {
				// source and receiver section are the same, make sure to include the patch self-stiffness value
				Preconditions.checkState(values[r].length == receiverDists.length);
				
				receiverVals = new double[receiverDists.length-1];
				if (r > 0)
					System.arraycopy(values[r], 0, receiverVals, 0, r);
				if (r < receiverVals.length)
					System.arraycopy(values[r], r+1, receiverVals, r, receiverVals.length - r);
				if (D) {
					System.out.println(source.getSectionId()+" -> "+receiver.getSectionId()+" sect-to-self patch calc, removing index "+r);
					System.out.println("\traw["+r+"]:\t"+DoubleStream.of(values[r]).mapToObj(String::valueOf).collect(Collectors.joining(",")));
					System.out.println("\tprocessed:\t"+DoubleStream.of(receiverVals).mapToObj(String::valueOf).collect(Collectors.joining(",")));
				}
			} else {
				receiverVals = values[r];
			}
			receiverDists[r] = new ReceiverDistribution(uniquePatchID(receiver.getSectionId(), r), receiverVals.length, receiverVals);
		}
		
		ReceiverDistribution[] aggregated = layers[0].aggregate(receiver.getSectionId(), receiverDists);
		
		if (cache != null && layers[0].isTerminal())
			cache.putPatchAggregated(layers[0], source, receiver, aggregated);
		
		if (D) {
			System.out.println(source.getSectionId()+" -> "+receiver.getSectionId()+", patchAgg="+layers[0]+":");
			for (ReceiverDistribution aggDist : aggregated)
				System.out.println("\t"+aggDist);
		}
		
		return aggregated;
	}
	
	private boolean isSectToSectCacheable() {
		return layers[1].isTerminal() && (layers[0].isTerminal() || layers[0] == AggregationMethod.FLATTEN);
	}
	
	private StiffnessAggregation getCachedSectToSect(FaultSection source, FaultSection receiver) {
		AggregationMethod patchAggMethod = null;
		if (layers[0].isTerminal()) {
			// we aggregated at the patch layer
			patchAggMethod = layers[0];
		}  else {
			Preconditions.checkState(layers[0] == AggregationMethod.FLATTEN);
		}
		
		if (cache == null)
			cache = calc.getAggregationCache(type);
		
		StiffnessAggregation aggregated = cache.getSectAggregated(patchAggMethod, source, receiver);
		if (aggregated == null) {
			// need to calculate and cache it
			ReceiverDistribution receiverPatchDist = flatten(receiver.getSectionId(), aggRecieverPatches(source, receiver));
//			Preconditions.checkState(receiverPatchDists.length == 1,
//					"should only have 1 flattened or procssed distribution at sect-to-sect if cacheable");
			int interactionCount = 0;
			aggregated = new StiffnessAggregation(receiverPatchDist.values, receiverPatchDist.totNumInteractions);
			if (D) {
				System.out.println(source.getSectionId()+" -> "+receiver.getSectionId()+" sect-to-sect "+layers[1]+":");
				System.out.println("\t"+receiverPatchDist);
			}
			cache.putSectAggregated(patchAggMethod, source, receiver, aggregated);
		} else if (D) {
			System.out.println(source.getSectionId()+" -> "+receiver.getSectionId()+" sect-to-sect "+layers[1]+" is CACHED:");
		}
		
		return aggregated;
	}
	
	private ReceiverDistribution[] aggSectToSect(FaultSection source, FaultSection receiver) {
		Preconditions.checkState(allowSectToSelf || source.getSectionId() != receiver.getSectionId(),
				"Source and receiver ID are the same and allowSectToSelf=false: %s", source.getSectionId());
		Preconditions.checkState(layers.length > 1, "Section-to-section aggregation layer not supplied");
		
		// check the cache if possible at this layer
		if (isSectToSectCacheable()) {
			StiffnessAggregation aggregated = getCachedSectToSect(source, receiver);
			double val = aggregated.get(layers[1]);
			if (D) System.out.println("\t"+layers[1]+": "+val);
			return new ReceiverDistribution[] { new ReceiverDistribution(receiver.getSectionId(),
					(int)aggregated.get(AggregationMethod.COUNT), new double[] { val }) };
		}
		
		ReceiverDistribution[] receiverPatchDists = aggRecieverPatches(source, receiver);
		ReceiverDistribution[] aggregated =  layers[1].aggregate(receiver.getSectionId(), receiverPatchDists);
		
		if (D) {
			System.out.println(source.getSectionId()+" -> "+receiver.getSectionId()+" sect-to-sect "+layers[1]+":");
			for (ReceiverDistribution aggDist : aggregated)
				System.out.println("\t"+aggDist);
		}
		
		return aggregated;
	}
	
	private double processUntilTerminal(int curLayer, int receiverID, ReceiverDistribution[] dists) {
		if (layers[curLayer].isTerminal()) {
			double val = layers[curLayer].get(dists);
			if (D) System.out.println("\t"+layers[curLayer]+": "+val);
			return val;
		} else {
			// need to process at this layer first
			dists = layers[curLayer].aggregate(receiverID, dists);
			
			if (D) {
				System.out.println("Intermediate layer "+curLayer+", "+layers[curLayer]+":");
				for (ReceiverDistribution aggDist : dists)
					System.out.println("\t"+aggDist);
			}
			return processUntilTerminal(curLayer+1, -1, dists);
		}
	}
	
	public double calc(FaultSection source, FaultSection receiver) {
		Preconditions.checkState(source.getSectionId() != receiver.getSectionId(),
				"Source and receiver ID are the same and allowSectToSelf=false: %s", source.getSectionId());
		Preconditions.checkState(layers.length > 1, "Section-to-section aggregation layer not supplied");
		
		if (isSectToSectCacheable()) {
			StiffnessAggregation aggregated = getCachedSectToSect(source, receiver);
			double val = aggregated.get(layers[1]);
			if (D) System.out.println("\t"+layers[1]+": "+val);
			return val;
		}
		
		ReceiverDistribution[] receiverPatchDists = aggRecieverPatches(source, receiver);
		
		if (D) System.out.println(source.getSectionId()+" -> "+receiver.getSectionId()+" sect-to-sect "+layers[1]+":");
		return processUntilTerminal(1, receiver.getSectionId(), receiverPatchDists);
	}
	
	private ReceiverDistribution[] collectMultiSectsToSect(List<FaultSection> sources, FaultSection receiver) {
		if (!allowSectToSelf) {
			List<FaultSection> filtered = new ArrayList<>(sources.size());
			int receiverID = receiver.getSectionId();
			for (int s=0; s<sources.size(); s++) {
				FaultSection source = sources.get(s);
				if (source.getSectionId() != receiverID)
					filtered.add(source);
			}
			sources = filtered;
		}
		Preconditions.checkState(!sources.isEmpty(), "No sources that aren't the receiver");
		// start assuming that it's a one-to-one mapping
		ReceiverDistribution[] receiverSectDists = new ReceiverDistribution[sources.size()];
		ArrayList<ReceiverDistribution> distsList = null;
		for (int s=0; s<sources.size(); s++) {
			FaultSection source = sources.get(s);
			ReceiverDistribution[] aggregated = aggSectToSect(source, receiver);
			if (distsList != null) {
				// we're already in list mode
				Collections.addAll(distsList, aggregated);
			} else if (aggregated.length != 1) {
				// we need to switch to list representation, not 1-to-1
				if (distsList == null) {
					distsList = new ArrayList<>(sources.size()*aggregated.length);
					// copy over that which we already added
					for (int i=0; i<s; i++)
						distsList.add(receiverSectDists[i]);
				}
				Collections.addAll(distsList, aggregated);
			} else {
				// still 1-to-1
				receiverSectDists[s] = aggregated[0];
			}
		}

		if (distsList != null) {
			if (distsList.size() > receiverSectDists.length)
				// reuse the array
				receiverSectDists = distsList.toArray(receiverSectDists);
			else
				receiverSectDists = distsList.toArray(new ReceiverDistribution[distsList.size()]);
		}
		return receiverSectDists;
	}
	
	private ReceiverDistribution[] aggSectsToSect(List<FaultSection> sources, FaultSection receiver) {
		Preconditions.checkState(layers.length > 2, "Sections-to-section aggregation layer not supplied");
		
		ReceiverDistribution[] receiverSectDists = collectMultiSectsToSect(sources, receiver);
		
		ReceiverDistribution[] aggregated = layers[2].aggregate(receiver.getSectionId(), receiverSectDists);
		
		if (D) {
			System.out.println(sources.stream().map(s -> s.getSectionId()).map(String::valueOf).collect(Collectors.joining(","))
					+" -> "+receiver.getSectionId()+" sects-to-sect "+layers[2]+":");
			for (ReceiverDistribution aggDist : aggregated)
				System.out.println("\t"+aggDist);
		}
		
		return aggregated;
	}
	
	public double calc(List<FaultSection> sources, FaultSection receiver) {
		Preconditions.checkState(layers.length > 2, "Sections-to-section aggregation layer not supplied");
		
		ReceiverDistribution[] receiverSectDists = collectMultiSectsToSect(sources, receiver);
		
		if (D) System.out.println(sources.stream().map(s -> s.getSectionId()).map(String::valueOf).collect(Collectors.joining(","))
					+" -> "+receiver.getSectionId()+" sects-to-sect "+layers[2]+":");
		return processUntilTerminal(2, receiver.getSectionId(), receiverSectDists);
	}
	
	public double calc(List<FaultSection> sources, List<FaultSection> receivers) {
		Preconditions.checkState(layers.length > 3, "Sections-to-sections aggregation layer not supplied");
		Preconditions.checkState(layers[3].isTerminal(), "Final layer must be terminal: %s", layers[3]);
		
		ReceiverDistribution[] receiverSectDists = new ReceiverDistribution[receivers.size()];
		ArrayList<ReceiverDistribution> distsList = null;
		for (int r=0; r<receivers.size(); r++) {
			FaultSection receiver = receivers.get(r);
			ReceiverDistribution[] aggregated = aggSectsToSect(sources, receiver);
			if (distsList != null) {
				// we're already in list mode
				Collections.addAll(distsList, aggregated);
			} else if (aggregated.length > 1) {
				// we need to switch to list representation, not 1-to-1
				if (distsList == null) {
					distsList = new ArrayList<>(sources.size()*aggregated.length);
					// copy over that which we already added
					for (int i=0; i<r; i++)
						distsList.add(receiverSectDists[i]);
				}
				Collections.addAll(distsList, aggregated);
			} else {
				// still 1-to-1
				receiverSectDists[r] = aggregated[0];
			}
		}

		if (distsList != null) {
			if (distsList.size() > receiverSectDists.length)
				// reuse the array
				receiverSectDists = distsList.toArray(receiverSectDists);
			else
				receiverSectDists = distsList.toArray(new ReceiverDistribution[distsList.size()]);
		}
//		System.out.println(receiverSectDists.length+" dists for "+sources.size()+" sources and "+receivers.size()+" receivers");
		
		double val = layers[3].get(receiverSectDists);
		if (D) {
			System.out.println(sources.stream().map(s -> s.getSectionId()).map(String::valueOf).collect(Collectors.joining(","))
					+" -> "+receivers.stream().map(s -> s.getSectionId()).map(String::valueOf).collect(Collectors.joining(","))
					+" sects-to-sects "+layers[3]+":");
			for (ReceiverDistribution aggDist : receiverSectDists)
				System.out.println("\t"+aggDist);
			System.out.println("\tVAL: "+val);
		}
		
		return val;
	}

	/**
	 * 
	 * @return true if the sequence of aggregations will result in values with units of stiffness (MPa),
	 * otherwise false (e.g., for fractions or counts)
	 */
	public boolean hasUnits() {
		boolean hasUnits = true;
		for (AggregationMethod layer : layers)
			hasUnits = hasUnits && layer.hasUnits;
		return hasUnits;
	}
	
	public AggregationMethod[] getAggregationLayers() {
		return layers;
	}
	
	public String getScalarName() {
//		String name = type.getName();
//		for (AggregationMethod aggMethod : layers) {
//			if (aggMethod == AggregationMethod.FRACT_POSITIVE)
//				name = "Fract ≥0";
//			else if (aggMethod == AggregationMethod.NUM_POSITIVE)
//				name = "Num ≥0";
//			else if (aggMethod == AggregationMethod.NUM_NEGATIVE)
//				name = "Num Negative";
//		}
//		return name;
		String name = null;
		for (int l=0; l<layers.length; l++) {
			if (layers[l] == AggregationMethod.FLATTEN || layers[l] == AggregationMethod.PASSTHROUGH
					|| (l > 0 && layers[l] == layers[l-1]))
				continue;
			
			if (layers[l] == AggregationMethod.FRACT_POSITIVE) {
				name = "Fract "+(name == null ? "" : "["+name+"]")+"≥0";
			} else if (layers[l] == AggregationMethod.NUM_NEGATIVE) {
				name = "Num "+(name == null ? "" : "["+name+"]")+"<0";
			} else if (layers[l] == AggregationMethod.NUM_POSITIVE) {
				name = "Num "+(name == null ? "" : "["+name+"]")+"≥0";
			} else if (layers[l] == AggregationMethod.NORM_BY_COUNT) {
				name = "["+(name == null ? "" : name)+"]"+"/Count";
			} else if (l == 0) {
				if (layers.length > 1 && layers[1] == layers[0])
					name = "Sect "+layers[l].name;
				else
					name = "Patch "+layers[l].name;
			} else if (layers[l] == AggregationMethod.RECEIVER_SUM) {
				// l is > 0 here
				if (layers[1].isTerminal())
					name = "Receiver Sect Aggregate"+(name == null ? "" : " ["+name+"]");
				else if (layers[0].isTerminal())
					name = "Receiver Patch Aggregate"+(name == null ? "" : " ["+name+"]");
				else
					name = "Receiver Aggregate "+(name == null ? "" : "["+name+"]");
			} else if (l == 1 && name == null) {
				name = "Sect "+layers[l].name;
			} else if (layers[l] != layers[l-1]) {
				name = layers[l].name+(name == null ? "" : " ["+name+"]");
			}
		}
		return name;
	}
	
	public String getScalarShortName() {
		return getScalarName().replaceAll("Receiver", "Rec").replaceAll("Median", "Mdn")
				.replaceAll("Aggregate", "Agg").replaceAll("imum", "").replaceAll("Sect", "S-")
				.replaceAll("Patch", "P-").replaceAll("Dominant", "Dom").replaceAll("Interaction", "Int").replaceAll(" ", "");
	}
	
	public StiffnessType getType() {
		return type;
	}
	
	@Override
	public String toString() {
		return Arrays.stream(layers).map(s -> s.toString()).collect(Collectors.joining(" -> ", "AggStiffnessCalc[", "]"));
	}

	public SubSectStiffnessCalculator getCalc() {
		return calc;
	}
	
	public static void main(String[] args) throws ZipException, IOException, DocumentException {
		File fssFile = new File("/home/kevin/Simulators/catalogs/rundir4983_stitched/fss/"
				+ "rsqsim_sol_m6.5_skip5000_sectArea0.2.zip");
		FaultSystemRupSet rupSet = FaultSystemIO.loadRupSet(fssFile);
		double lambda = 30000;
		double mu = 30000;
		double coeffOfFriction = 0.5;
		double gridSpacing = 4d;
		List<? extends FaultSection> subSects = rupSet.getFaultSectionDataList();
		SubSectStiffnessCalculator calc = new SubSectStiffnessCalculator(
				subSects, gridSpacing, lambda, mu, coeffOfFriction);
		calc.setPatchAlignment(PatchAlignment.FILL_OVERLAP);
//		calc.setPatchAlignment(PatchAlignment.CENTER);
		
		AggregatedStiffnessCalculator aggCalc = new AggregatedStiffnessCalculator(StiffnessType.CFF, calc, true,
//				AggregationMethod.SUM, AggregationMethod.PASSTHROUGH,
//				AggregationMethod.RECEIVER_SUM, AggregationMethod.FRACT_POSITIVE);
//				AggregationMethod.SUM, AggregationMethod.FLATTEN,
//				AggregationMethod.FLATTEN, AggregationMethod.FRACT_POSITIVE);
//				AggregationMethod.FLATTEN, AggregationMethod.MEDIAN,
//				AggregationMethod.SUM, AggregationMethod.SUM);
				AggregationMethod.NUM_POSITIVE, AggregationMethod.SUM,
				AggregationMethod.NORM_BY_COUNT, AggregationMethod.FRACT_POSITIVE);
		
//		FaultSection s1 = subSects.get(0);
//		FaultSection s2 = subSects.get(1);
//		FaultSection s3 = subSects.get(2);
////		aggCalc.calc(s2, s1);
////		aggCalc.calc(s3, s1);
//		aggCalc.calc(Lists.newArrayList(s2, s3), s1);
		int targetNumSects = 2;
		
		for (int r=0; r<rupSet.getNumRuptures(); r++) {
			List<FaultSection> rupSects = rupSet.getFaultSectionDataForRupture(r);
			if (rupSects.size() != targetNumSects)
				continue;
//			System.out.println("Rupture "+r+": "+Joiner.on(",").join(rupSet.getSectionsIndicesForRup(r)));
			System.out.println("Rupture "+r+": "+rupSects.stream().map(s -> s.getSectionId())
					.map(String::valueOf).collect(Collectors.joining(",")));
			aggCalc.calc(rupSects, rupSects);
			break;
		}
	}

}
