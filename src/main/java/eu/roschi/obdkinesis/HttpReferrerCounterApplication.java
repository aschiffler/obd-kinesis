/*
 * Copyright 2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package eu.roschi.obdkinesis;

import java.net.UnknownHostException;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Region;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.clientlibrary.interfaces.IRecordProcessorFactory;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.InitialPositionInStream;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.KinesisClientLibConfiguration;
import com.amazonaws.services.kinesis.clientlibrary.lib.worker.Worker;

import eu.roschi.obdkinesis.kcl.CountingRecordProcessorFactory;
import eu.roschi.obdkinesis.kcl.persistence.CountPersister;
import eu.roschi.obdkinesis.kcl.persistence.ddb.DynamoDBCountPersister;
import eu.roschi.obdkinesis.model.HttpReferrerPair;
import eu.roschi.obdkinesis.utils.DynamoDBUtils;
import eu.roschi.obdkinesis.utils.SampleUtils;
import eu.roschi.obdkinesis.utils.StreamUtils;

/**
 * Amazon Kinesis application to count distinct {@link HttpReferrerPair}s over a sliding window. Counts are persisted
 * every update interval by a {@link CountPersister}.
 */
public class HttpReferrerCounterApplication {
    private static final Log LOG = LogFactory.getLog(HttpReferrerCounterApplication.class);

    // Count occurrences of HTTP referrer pairs over a range of 10 seconds
    private static int COMPUTE_RANGE_FOR_COUNTS_IN_MILLIS;
    // Update the counts every 1 second
    private static int COMPUTE_INTERVAL_IN_MILLIS;

    /**
     * Start the Kinesis Client application.
     * 
     * @param args Expecting 4 arguments: Application name to use for the Kinesis Client Application, Stream name to
     *        read from, DynamoDB table name to persist counts into, and the AWS region in which these resources
     *        exist or should be created.
     */
    public static void main(String[] args) throws UnknownHostException {
        
    	if (args.length != 2) {
            System.err.println("Using default values");
        	COMPUTE_RANGE_FOR_COUNTS_IN_MILLIS = 30000;
        	COMPUTE_INTERVAL_IN_MILLIS = 2000;
        } else{
        	COMPUTE_RANGE_FOR_COUNTS_IN_MILLIS = Integer.parseInt(args[0]);
        	COMPUTE_INTERVAL_IN_MILLIS = Integer.parseInt(args[1]);
        	System.err.println("Using values " + Integer.parseInt(args[0])
        			+ " width " + Integer.parseInt(args[1]) + " rate");
        }
        String applicationName = "obd_kinesis";
        String streamName = "obd_input_stream";
        String countsTableName = "obd_kinesis_count";
        Region region = SampleUtils.parseRegion("us-west-2");

        AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();
        ClientConfiguration clientConfig = SampleUtils.configureUserAgentForSample(new ClientConfiguration());
        AmazonKinesis kinesis = new AmazonKinesisClient(credentialsProvider, clientConfig);
        kinesis.setRegion(region);
        AmazonDynamoDB dynamoDB = new AmazonDynamoDBClient(credentialsProvider, clientConfig);
        dynamoDB.setRegion(region);

        // Creates a stream to write to, if it doesn't exist
        StreamUtils streamUtils = new StreamUtils(kinesis);
        streamUtils.createStreamIfNotExists(streamName, 2);
        LOG.info(String.format("%s stream is ready for use", streamName));

        DynamoDBUtils dynamoDBUtils = new DynamoDBUtils(dynamoDB);
        dynamoDBUtils.createCountTableIfNotExists(countsTableName);
        LOG.info(String.format("%s DynamoDB table is ready for use", countsTableName));

        String workerId = String.valueOf(UUID.randomUUID());
        LOG.info(String.format("Using working id: %s", workerId));
        KinesisClientLibConfiguration kclConfig =
                new KinesisClientLibConfiguration(applicationName, streamName, credentialsProvider, workerId);
        kclConfig.withCommonClientConfig(clientConfig);
        kclConfig.withRegionName(region.getName());
        kclConfig.withInitialPositionInStream(InitialPositionInStream.LATEST);

        // Persist counts to DynamoDB
        DynamoDBCountPersister persister =
                new DynamoDBCountPersister(dynamoDBUtils.createMapperForTable(countsTableName));

        IRecordProcessorFactory recordProcessor =
                new CountingRecordProcessorFactory<HttpReferrerPair>(HttpReferrerPair.class,
                        persister,
                        COMPUTE_RANGE_FOR_COUNTS_IN_MILLIS,
                        COMPUTE_INTERVAL_IN_MILLIS);

        Worker worker = new Worker(recordProcessor, kclConfig);

        int exitCode = 0;
        try {
            worker.run();
        } catch (Throwable t) {
            LOG.error("Caught throwable while processing data.", t);
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
