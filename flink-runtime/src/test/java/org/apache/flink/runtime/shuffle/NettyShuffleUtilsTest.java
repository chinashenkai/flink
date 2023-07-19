/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.shuffle;

import org.apache.flink.metrics.groups.UnregisteredMetricsGroup;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.deployment.InputGateDeploymentDescriptor;
import org.apache.flink.runtime.deployment.ResultPartitionDeploymentDescriptor;
import org.apache.flink.runtime.deployment.TaskDeploymentDescriptorFactory.ShuffleDescriptorAndIndex;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.io.network.NettyShuffleEnvironment;
import org.apache.flink.runtime.io.network.NettyShuffleEnvironmentBuilder;
import org.apache.flink.runtime.io.network.partition.ResultPartition;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGateBuilder;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.util.TestLogger;

import org.apache.flink.shaded.guava30.com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.apache.flink.runtime.executiongraph.ExecutionGraphTestUtils.createExecutionAttemptId;
import static org.apache.flink.runtime.io.network.partition.ResultPartitionType.BLOCKING;
import static org.apache.flink.runtime.io.network.partition.ResultPartitionType.PIPELINED_BOUNDED;
import static org.apache.flink.runtime.util.NettyShuffleDescriptorBuilder.createRemoteWithIdAndLocation;
import static org.junit.Assert.assertEquals;

/** Tests for {@link NettyShuffleUtils}. */
public class NettyShuffleUtilsTest extends TestLogger {

    /**
     * This test verifies that the {@link NettyShuffleEnvironment} requires buffers as expected, so
     * that the required shuffle memory size returned by {@link
     * ShuffleMaster#computeShuffleMemorySizeForTask(TaskInputsOutputsDescriptor)} is correct.
     */
    @Test
    public void testComputeRequiredNetworkBuffers() throws Exception {
        // NOTE: NettyShuffleMaster.buffersPerInputChannel 'taskmanager.network.memory.buffers-per-channel'
        int numBuffersPerChannel = 5;

        // NOTE: NettyShuffleMaster.numFloatingBuffersPerGate 'taskmanager.network.memory.floating-buffers-per-gate'
        int numBuffersPerGate = 8;

        // NOTE: NettyShuffleMaster.maxRequiredBuffersPerGate 'taskmanager.network.sort-shuffle.min-parallelism'
        Optional<Integer> maxRequiredBuffersPerGate = Optional.of(Integer.MAX_VALUE);

        // NOTE: NettyShuffleMaster.sortShuffleMinParallelism taskmanager.network.sort-shuffle.min-parallelism
        int sortShuffleMinParallelism = 8;

        // NOTE: NettyShuffleMaster.sortShuffleMinBuffers  taskmanager.network.sort-shuffle.min-buffers
        int numSortShuffleMinBuffers = 12;

        // NOTE: input jobVertex operator ID
        IntermediateDataSetID ids1 = new IntermediateDataSetID();
        IntermediateDataSetID ids2 = new IntermediateDataSetID();

        // NOTE: num of input-channel for every upstream
        int numChannels1 = 3;
        int numChannels2 = 4;

        // NOTE: output jobVertex operator ID
        // ds0/ds1 is jobVertex's name
        IntermediateDataSetID ds1 = new IntermediateDataSetID();
        IntermediateDataSetID ds2 = new IntermediateDataSetID();
        IntermediateDataSetID ds3 = new IntermediateDataSetID();

        // NOTE: num of output subpartitions for downstream
        int numSubs1 = 5; // pipelined shuffle
        int numSubs2 = 6; // hash blocking shuffle
        int numSubs3 = 10; // sort blocking shuffle

        // NOTE: output subpartition 5, 6, 10
        Map<IntermediateDataSetID, Integer> subpartitionNums =
                ImmutableMap.of(ds1, numSubs1, ds2, numSubs2, ds3, numSubs3);

        // NOTE: output
        Map<IntermediateDataSetID, ResultPartitionType> partitionTypes =
                ImmutableMap.of(ds1, PIPELINED_BOUNDED, ds2, BLOCKING, ds3, BLOCKING);


        // NOTE: input channel 3, 4
        Map<IntermediateDataSetID, Integer> numInputChannels =
                ImmutableMap.of(ids1, numChannels1, ids2, numChannels2);

        // NOTE: input partition count
        Map<IntermediateDataSetID, Integer> partitionReuseCount =
                ImmutableMap.of(ids1, 1, ids2, 1);

        // NOTE: input Type
        Map<IntermediateDataSetID, ResultPartitionType> inputPartitionTypes =
                ImmutableMap.of(ids1, PIPELINED_BOUNDED, ids2, BLOCKING);

        int numTotalBuffers =
                NettyShuffleUtils.computeNetworkBuffersForAnnouncing(
                        numBuffersPerChannel,
                        numBuffersPerGate,
                        maxRequiredBuffersPerGate,
                        sortShuffleMinParallelism,
                        numSortShuffleMinBuffers,
                        numInputChannels,
                        partitionReuseCount,
                        subpartitionNums,
                        inputPartitionTypes,
                        partitionTypes);

        NettyShuffleEnvironment sEnv =
                new NettyShuffleEnvironmentBuilder()
                        .setNumNetworkBuffers(numTotalBuffers)
                        .setNetworkBuffersPerChannel(numBuffersPerChannel)
                        .setSortShuffleMinBuffers(numSortShuffleMinBuffers)
                        .setSortShuffleMinParallelism(sortShuffleMinParallelism)
                        .build();

        SingleInputGate inputGate1 = createInputGate(sEnv, PIPELINED_BOUNDED, numChannels1);
        inputGate1.setup();

        SingleInputGate inputGate2 = createInputGate(sEnv, BLOCKING, numChannels2);
        inputGate2.setup();

        ResultPartition resultPartition1 = createResultPartition(sEnv, PIPELINED_BOUNDED, numSubs1);
        resultPartition1.setup();

        ResultPartition resultPartition2 = createResultPartition(sEnv, BLOCKING, numSubs2);
        resultPartition2.setup();

        ResultPartition resultPartition3 = createResultPartition(sEnv, BLOCKING, numSubs3);
        resultPartition3.setup();

        int expected =
                calculateBuffersConsumption(inputGate1)
                        + calculateBuffersConsumption(inputGate2)
                        + calculateBuffersConsumption(resultPartition1)
                        + calculateBuffersConsumption(resultPartition2)
                        + calculateBuffersConsumption(resultPartition3);
        assertEquals(expected, numTotalBuffers);

        inputGate1.close();
        inputGate2.close();
        resultPartition1.close();
        resultPartition2.close();
        resultPartition3.close();
    }

    private SingleInputGate createInputGate(
            NettyShuffleEnvironment network,
            ResultPartitionType resultPartitionType,
            int numInputChannels)
            throws IOException {

        ShuffleDescriptorAndIndex[] shuffleDescriptors =
                new ShuffleDescriptorAndIndex[numInputChannels];
        for (int i = 0; i < numInputChannels; i++) {
            shuffleDescriptors[i] =
                    new ShuffleDescriptorAndIndex(
                            createRemoteWithIdAndLocation(
                                    new IntermediateResultPartitionID(), ResourceID.generate()),
                            i);
        }

        InputGateDeploymentDescriptor inputGateDeploymentDescriptor =
                new InputGateDeploymentDescriptor(
                        new IntermediateDataSetID(), resultPartitionType, 0, shuffleDescriptors);

        ExecutionAttemptID consumerID = createExecutionAttemptId();
        Collection<SingleInputGate> inputGates =
                network.createInputGates(
                        network.createShuffleIOOwnerContext(
                                "", consumerID, new UnregisteredMetricsGroup()),
                        SingleInputGateBuilder.NO_OP_PRODUCER_CHECKER,
                        Collections.singletonList(inputGateDeploymentDescriptor));

        return inputGates.iterator().next();
    }

    private ResultPartition createResultPartition(
            NettyShuffleEnvironment network,
            ResultPartitionType resultPartitionType,
            int numSubpartitions) {

        ShuffleDescriptor shuffleDescriptor =
                createRemoteWithIdAndLocation(
                        new IntermediateResultPartitionID(), ResourceID.generate());

        PartitionDescriptor partitionDescriptor =
                new PartitionDescriptor(
                        new IntermediateDataSetID(),
                        2,
                        shuffleDescriptor.getResultPartitionID().getPartitionId(),
                        resultPartitionType,
                        numSubpartitions,
                        0,
                        false,
                        true);
        ResultPartitionDeploymentDescriptor resultPartitionDeploymentDescriptor =
                new ResultPartitionDeploymentDescriptor(partitionDescriptor, shuffleDescriptor, 1);

        ExecutionAttemptID consumerID = createExecutionAttemptId();
        Collection<ResultPartition> resultPartitions =
                network.createResultPartitionWriters(
                        network.createShuffleIOOwnerContext(
                                "", consumerID, new UnregisteredMetricsGroup()),
                        Collections.singletonList(resultPartitionDeploymentDescriptor));

        return resultPartitions.iterator().next();
    }

    private int calculateBuffersConsumption(SingleInputGate inputGate) throws Exception {
        inputGate.setChannelStateWriter(ChannelStateWriter.NO_OP);
        inputGate.finishReadRecoveredState();
        while (!inputGate.getStateConsumedFuture().isDone()) {
            inputGate.pollNext();
        }
        inputGate.convertRecoveredInputChannels();

        int ret = 0;
        for (InputChannel ch : inputGate.getInputChannels().values()) {
            RemoteInputChannel rChannel = (RemoteInputChannel) ch;
            ret += rChannel.getNumberOfAvailableBuffers();
        }
        ret += inputGate.getBufferPool().getMaxNumberOfMemorySegments();
        return ret;
    }

    private int calculateBuffersConsumption(ResultPartition partition) {
        if (!partition.getPartitionType().canBePipelinedConsumed()) {
            return partition.getBufferPool().getNumberOfRequiredMemorySegments();
        } else {
            return partition.getBufferPool().getMaxNumberOfMemorySegments();
        }
    }
}
