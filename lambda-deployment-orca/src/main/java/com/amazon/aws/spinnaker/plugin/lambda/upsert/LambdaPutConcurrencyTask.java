/*
 * Copyright 2018 Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazon.aws.spinnaker.plugin.lambda.upsert;

import com.amazon.aws.spinnaker.plugin.lambda.LambdaCloudOperationOutput;
import com.amazon.aws.spinnaker.plugin.lambda.LambdaStageBaseTask;
import com.amazon.aws.spinnaker.plugin.lambda.upsert.model.LambdaConcurrencyInput;
import com.amazon.aws.spinnaker.plugin.lambda.utils.LambdaCloudDriverResponse;
import com.amazon.aws.spinnaker.plugin.lambda.utils.LambdaCloudDriverUtils;
import com.amazon.aws.spinnaker.plugin.lambda.utils.LambdaDefinition;
import com.amazon.aws.spinnaker.plugin.lambda.utils.LambdaStageConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.clouddriver.config.CloudDriverConfigurationProperties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.pf4j.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LambdaPutConcurrencyTask implements LambdaStageBaseTask {
    private static Logger logger = LoggerFactory.getLogger(LambdaPutConcurrencyTask.class);
    private static final ObjectMapper objMapper = new ObjectMapper();
    private static String CLOUDDRIVER_PROVISIONED_CONCURRENCY_PATH = "/aws/ops/putLambdaProvisionedConcurrency";
    private static String CLOUDDRIVER_RESERVED_CONCURRENCY_PATH = "/aws/ops/putLambdaReservedConcurrency";

    @Autowired
    CloudDriverConfigurationProperties props;

    @Autowired
    private LambdaCloudDriverUtils utils;
    private  String cloudDriverUrl;

    @NotNull
    @Override
    public TaskResult execute(@NotNull StageExecution stage) {
        logger.debug("Executing LambdaPutConcurrencyTask...");
        cloudDriverUrl = props.getCloudDriverBaseUrl();
        prepareTask(stage);
        LambdaConcurrencyInput inp = utils.getInput(stage, LambdaConcurrencyInput.class);
        inp.setAppName(stage.getExecution().getApplication());
        LambdaDefinition lambdaDefinition = utils.retrieveLambdaFromCache(stage,false);
        System.out.println("lambdaDefinition: " + lambdaDefinition);
        String result = utils.getFromCloudDriver(cloudDriverUrl+"/functions?region="+inp.getRegion()+"&account="+inp.getAccount()+"&functionName="+inp.getFunctionName());
        System.out.println("result: " + result);
//        if ((inp.getProvisionedConcurrentExecutions() == 0)  && (inp.getReservedConcurrentExecutions() == null)){
//            addToOutput(stage, "LambdaPutConcurrencyTask" , "Lambda concurrency : nothing to update");
//            return taskComplete(stage);
//        }

        LambdaCloudOperationOutput output = putConcurrency(inp);
        addCloudOperationToContext(stage, output, LambdaStageConstants.putConcurrencyUrlKey);
        return taskComplete(stage);
    }

    private LambdaCloudOperationOutput putConcurrency(LambdaConcurrencyInput inp) {
        inp.setCredentials(inp.getAccount());
        if (inp.getProvisionedConcurrentExecutions() != 0 &&
                StringUtils.isNotNullOrEmpty(inp.getAliasName())) {
            return putProvisionedConcurrency(inp);
        }
        if (inp.getReservedConcurrentExecutions() != null) {
            logger.debug("logger?");
            System.out.println("diferente de null");
            return putReservedConcurrency(inp);
        }
        System.out.println("Es igual a: " + inp.getReservedConcurrentExecutions());
        return putReservedConcurrency(inp);
        //return deleteReservedConcurrency();
//        return LambdaCloudOperationOutput.builder().build();
    }

    private LambdaCloudOperationOutput putReservedConcurrency( LambdaConcurrencyInput inp) {
        String rawString = utils.asString(inp);
        String endPoint = cloudDriverUrl + CLOUDDRIVER_RESERVED_CONCURRENCY_PATH;
        System.out.println("endppoint: " + endPoint);
        System.out.println("rawString: " + rawString);
        LambdaCloudDriverResponse respObj = utils.postToCloudDriver(endPoint, rawString);
        String url = cloudDriverUrl + respObj.getResourceUri();
        System.out.println("Posted to cloudDriver for putReservedConcurrency: " + url);
        logger.debug("Posted to cloudDriver for putReservedConcurrency: " + url);
        LambdaCloudOperationOutput operationOutput = LambdaCloudOperationOutput.builder().resourceId(respObj.getId()).url(url).build();
        System.out.println("operationOutput: " + operationOutput.toString());
        operationOutput.getOutputMap().forEach((k, v) -> System.out.print("key " + k + ": " + v));
        return operationOutput;
    }

    private LambdaCloudOperationOutput putProvisionedConcurrency(LambdaConcurrencyInput inp) {
        inp.setQualifier(inp.getAliasName());
        String rawString = utils.asString(inp);
        String endPoint = cloudDriverUrl + CLOUDDRIVER_PROVISIONED_CONCURRENCY_PATH;
        LambdaCloudDriverResponse respObj = utils.postToCloudDriver(endPoint, rawString);
        String url = cloudDriverUrl + respObj.getResourceUri();
        logger.debug("Posted to cloudDriver for putProvisionedConcurrency: " + url);
        LambdaCloudOperationOutput operationOutput = LambdaCloudOperationOutput.builder().resourceId(respObj.getId()).url(url).build();
        return operationOutput;
    }

    @Nullable
    @Override
    public TaskResult onTimeout(@NotNull StageExecution stage) {
        return TaskResult.builder(ExecutionStatus.SKIPPED).build();
    }

    @Override
    public void onCancel(@NotNull StageExecution stage) {
    }
}