package com.jstarcraft.recommendation.recommender.context.rating;

import com.jstarcraft.ai.math.structure.MathCalculator;
import com.jstarcraft.ai.math.structure.matrix.DenseMatrix;
import com.jstarcraft.ai.math.structure.matrix.MatrixScalar;
import com.jstarcraft.ai.math.structure.vector.DenseVector;
import com.jstarcraft.ai.math.structure.vector.SparseVector;
import com.jstarcraft.ai.math.structure.vector.VectorScalar;
import com.jstarcraft.ai.utility.MathUtility;
import com.jstarcraft.core.utility.RandomUtility;
import com.jstarcraft.recommendation.configure.Configuration;
import com.jstarcraft.recommendation.data.DataSpace;
import com.jstarcraft.recommendation.data.accessor.InstanceAccessor;
import com.jstarcraft.recommendation.data.accessor.SampleAccessor;
import com.jstarcraft.recommendation.recommender.SocialRecommender;
import com.jstarcraft.recommendation.utility.LogisticUtility;

/**
 * 
 * SocialMF推荐器
 * 
 * <pre>
 *  matrix factorization technique with trust propagation for recommendation in social networks
 * 参考LibRec团队
 * </pre>
 * 
 * @author Birdy
 *
 */
public class SocialMFRecommender extends SocialRecommender {

	@Override
	public void prepare(Configuration configuration, SampleAccessor marker, InstanceAccessor model, DataSpace space) {
		super.prepare(configuration, marker, model, space);
		userFactors = DenseMatrix.valueOf(numberOfUsers, numberOfFactors);
		userFactors.iterateElement(MathCalculator.SERIAL, (scalar) -> {
			scalar.setValue(RandomUtility.randomFloat(1F));
		});
		itemFactors = DenseMatrix.valueOf(numberOfItems, numberOfFactors);
		itemFactors.iterateElement(MathCalculator.SERIAL, (scalar) -> {
			scalar.setValue(RandomUtility.randomFloat(1F));
		});
	}

	// TODO 需要重构
	@Override
	protected void doPractice() {
		DenseVector socialFactors = DenseVector.valueOf(numberOfFactors);
		for (int iterationStep = 1; iterationStep <= numberOfEpoches; iterationStep++) {
			totalLoss = 0F;
			DenseMatrix userDeltas = DenseMatrix.valueOf(numberOfUsers, numberOfFactors);
			DenseMatrix itemDeltas = DenseMatrix.valueOf(numberOfItems, numberOfFactors);

			// rated items
			for (MatrixScalar term : trainMatrix) {
				int userIndex = term.getRow();
				int itemIndex = term.getColumn();
				float rate = term.getValue();
				float predict = super.predict(userIndex, itemIndex);
				float error = LogisticUtility.getValue(predict) - normalize(rate);
				totalLoss += error * error;
				error = LogisticUtility.getGradient(predict) * error;
				for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
					float userFactor = userFactors.getValue(userIndex, factorIndex);
					float itemFactor = itemFactors.getValue(itemIndex, factorIndex);
					userDeltas.shiftValue(userIndex, factorIndex, error * itemFactor + userRegularization * userFactor);
					itemDeltas.shiftValue(itemIndex, factorIndex, error * userFactor + itemRegularization * itemFactor);
					totalLoss += userRegularization * userFactor * userFactor + itemRegularization * itemFactor * itemFactor;
				}
			}

			// social regularization
			for (int userIndex = 0; userIndex < numberOfUsers; userIndex++) {
				SparseVector trusterVector = socialMatrix.getRowVector(userIndex);
				int numTrusters = trusterVector.getElementSize();
				if (numTrusters == 0) {
					continue;
				}
				socialFactors.setValues(0F);
				for (VectorScalar trusterTerm : trusterVector) {
					int trusterIndex = trusterTerm.getIndex();
					for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
						socialFactors.setValue(factorIndex, socialFactors.getValue(factorIndex) + trusterTerm.getValue() * userFactors.getValue(trusterIndex, factorIndex));
					}
				}
				for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
					float error = userFactors.getValue(userIndex, factorIndex) - socialFactors.getValue(factorIndex) / numTrusters;
					userDeltas.shiftValue(userIndex, factorIndex, socialRegularization * error);
					totalLoss += socialRegularization * error * error;
				}

				// those who trusted user u
				SparseVector trusteeVector = socialMatrix.getColumnVector(userIndex);
				int numTrustees = trusteeVector.getElementSize();
				for (VectorScalar trusteeTerm : trusteeVector) {
					int trusteeIndex = trusteeTerm.getIndex();
					trusterVector = socialMatrix.getRowVector(trusteeIndex);
					numTrusters = trusterVector.getElementSize();
					if (numTrusters == 0) {
						continue;
					}
					socialFactors.setValues(0F);
					for (VectorScalar trusterTerm : trusterVector) {
						int trusterIndex = trusterTerm.getIndex();
						for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
							socialFactors.setValue(factorIndex, socialFactors.getValue(factorIndex) + trusterTerm.getValue() * userFactors.getValue(trusterIndex, factorIndex));
						}
					}
					for (int factorIndex = 0; factorIndex < numberOfFactors; factorIndex++) {
						userDeltas.shiftValue(userIndex, factorIndex, -socialRegularization * (trusteeTerm.getValue() / numTrustees) * (userFactors.getValue(trusteeIndex, factorIndex) - socialFactors.getValue(factorIndex) / numTrusters));
					}
				}
			}
			// update user factors
			userFactors.iterateElement(MathCalculator.PARALLEL, (scalar) -> {
				int row = scalar.getRow();
				int column = scalar.getColumn();
				float value = scalar.getValue();
				scalar.setValue(value + userDeltas.getValue(row, column) * -learnRate);
			});
			itemFactors.iterateElement(MathCalculator.PARALLEL, (scalar) -> {
				int row = scalar.getRow();
				int column = scalar.getColumn();
				float value = scalar.getValue();
				scalar.setValue(value + itemDeltas.getValue(row, column) * -learnRate);
			});

			totalLoss *= 0.5D;
			if (isConverged(iterationStep) && isConverged) {
				break;
			}
			isLearned(iterationStep);
			currentLoss = totalLoss;
		}
	}

	@Override
	public float predict(int[] dicreteFeatures, float[] continuousFeatures) {
		int userIndex = dicreteFeatures[userDimension];
		int itemIndex = dicreteFeatures[itemDimension];
		float predict = super.predict(userIndex, itemIndex);
		return denormalize(LogisticUtility.getValue(predict));
	}

}
