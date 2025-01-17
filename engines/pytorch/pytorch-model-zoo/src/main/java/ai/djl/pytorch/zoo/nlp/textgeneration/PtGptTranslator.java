/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.pytorch.zoo.nlp.textgeneration;

import ai.djl.modality.nlp.generate.CausalLMOutput;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;

import java.util.stream.Collectors;

/** The {@link ai.djl.translate.Translator} for PyTorch GPT2 model. */
public class PtGptTranslator implements NoBatchifyTranslator<NDList, CausalLMOutput> {

    private long kvDim;
    private int numAttentionHeads;
    private int numLayers;
    private String tupleName;

    /**
     * Constructs a new instance of {@code PtGptTranslator}.
     *
     * @param kvDim the kv dimension
     * @param numAttentionHeads the number of attention heads
     * @param numLayers the number of layers
     */
    public PtGptTranslator(long kvDim, int numAttentionHeads, int numLayers) {
        this.kvDim = kvDim;
        this.numAttentionHeads = numAttentionHeads;
        this.numLayers = numLayers;
        tupleName = "past_key_values(" + numLayers + ',' + 2 + ')';
    }

    /** {@inheritDoc} */
    @Override
    public NDList processInput(TranslatorContext ctx, NDList input) throws Exception {
        NDManager manager = ctx.getNDManager();
        if (input.size() == 3) {
            ctx.setAttachment("initialCall", Boolean.TRUE);
            long batchSize = input.get(0).getShape().get(0);
            NDArray attentionMask = input.get(2);
            attentionMask =
                    manager.zeros(new Shape(batchSize, 1), DataType.INT64)
                            .concat(attentionMask, -1);
            input.set(2, attentionMask);
            addInitialDummyPastKeyValues(input, input.get(0), manager);
        }

        return input;
    }

    /** {@inheritDoc} */
    @Override
    public CausalLMOutput processOutput(TranslatorContext ctx, NDList output) throws Exception {
        NDArray logitsOutput = output.get(0);
        NDManager manager = output.getManager();
        NDList pastKeyValuesOutput = output.subNDList(1, numLayers * 2 + 1);
        NDArray hiddenStatesOutput;
        if (output.size() > numLayers * 2 + 1) {
            hiddenStatesOutput = output.get(numLayers * 2 + 1);
        } else {
            // If the traced_GPT2 model outputs hiddenStates, then this is not executed. If the
            // provided traced model doesn't output hiddenStates, we can throw a warning here.
            hiddenStatesOutput = manager.zeros(new Shape(1));
        }

        if (ctx.getAttachment("initialCall") != null) {
            NDIndex index2 = new NDIndex(":, :, 1:, ...");
            pastKeyValuesOutput =
                    new NDList(
                            pastKeyValuesOutput.stream()
                                    .map(object -> object.get(index2))
                                    .collect(Collectors.toList()));
        }

        for (NDArray array : pastKeyValuesOutput) {
            array.setName(tupleName);
        }

        return new CausalLMOutput(logitsOutput, hiddenStatesOutput, pastKeyValuesOutput);
    }

    private void addInitialDummyPastKeyValues(NDList list, NDArray inputIds, NDManager manager) {
        long numBatch = inputIds.getShape().get(0);
        for (int i = 0; i < numLayers * 2; ++i) {
            NDArray array = manager.zeros(new Shape(numBatch, numAttentionHeads, 1, kvDim));
            array.setName(tupleName);
            list.add(array);
        }
    }
}
