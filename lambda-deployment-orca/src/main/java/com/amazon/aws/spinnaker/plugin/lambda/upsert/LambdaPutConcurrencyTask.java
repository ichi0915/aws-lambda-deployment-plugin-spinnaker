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
import com.amazon.aws.spinnaker.plugin.lambda.utils.LambdaStageConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.orca.api.pipeline.TaskResult;
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus;
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution;
import com.netflix.spinnaker.orca.clouddriver.config.CloudDriverConfigurationProperties;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.pf4j.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class LambdaPutConcurrencyTask implements LambdaStageBaseTask {
    private static Logger logger = LoggerFactory.getLogger(LambdaPutConcurrencyTask.class);
    private static final ObjectMapper objMapper = new ObjectMapper();
    private static String CLOUDDRIVER_PUT_PROVISIONED_CONCURRENCY_PATH = "/aws/ops/putLambdaProvisionedConcurrency";
    private static String CLOUDDRIVER_PUT_RESERVED_CONCURRENCY_PATH = "/aws/ops/putLambdaReservedConcurrency";


    @Autowired
    CloudDriverConfigurationProperties props;

    @Autowired
    private LambdaCloudDriverUtils utils;
    private  String cloudDriverUrl;

    @SneakyThrows
    @NotNull
    @Override
    public TaskResult execute(@NotNull StageExecution stage) {
        logger.debug("Executing LambdaPutConcurrencyTask...");
        cloudDriverUrl = props.getCloudDriverBaseUrl();
        prepareTask(stage);
        LambdaConcurrencyInput inp = utils.getInput(stage, LambdaConcurrencyInput.class);
        inp.setAppName(stage.getExecution().getApplication());

        if (inp.getReservedConcurrentExecutions() == null && Optional.ofNullable(inp.getProvisionedConcurrentExecutions()).orElse(0) == 0)
        {
            addToOutput(stage, "LambdaPutConcurrencyTask" , "Lambda concurrency : nothing to update");
            return taskComplete(stage);
        }

        this.checkPublishBeforeRouting(stage);

        LambdaCloudOperationOutput output = putConcurrency(inp);
        addCloudOperationToContext(stage, output, LambdaStageConstants.putConcurrencyUrlKey);
        return taskComplete(stage);
    }

    private LambdaCloudOperationOutput putConcurrency(LambdaConcurrencyInput inp) {
        inp.setCredentials(inp.getAccount());
        if (inp.getProvisionedConcurrentExecutions() != null
                && inp.getProvisionedConcurrentExecutions() != 0
                && StringUtils.isNotNullOrEmpty(inp.getAliasName())) {
            return putProvisionedConcurrency(inp);
        }
        if (inp.getReservedConcurrentExecutions() != null) {
            return putReservedConcurrency(inp);
        }
        return LambdaCloudOperationOutput.builder().build();
    }

    private LambdaCloudOperationOutput putReservedConcurrency( LambdaConcurrencyInput inp) {
        String rawString = utils.asString(inp);
        String endPoint = cloudDriverUrl + CLOUDDRIVER_PUT_RESERVED_CONCURRENCY_PATH;
        LambdaCloudDriverResponse respObj = utils.postToCloudDriver(endPoint, rawString);
        String url = cloudDriverUrl + respObj.getResourceUri();
        logger.debug("Posted to cloudDriver for putReservedConcurrency: " + url);
        LambdaCloudOperationOutput operationOutput = LambdaCloudOperationOutput.builder().resourceId(respObj.getId()).url(url).build();
        return operationOutput;
    }

    private LambdaCloudOperationOutput putProvisionedConcurrency(LambdaConcurrencyInput inp) {
        inp.setQualifier(inp.getAliasName());
        String rawString = utils.asString(inp);
        String endPoint = cloudDriverUrl + CLOUDDRIVER_PUT_PROVISIONED_CONCURRENCY_PATH;
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

    /*
     * Why the sleep for 3 min
     *
     * There is an issue with AWS lambdas that if you update aliasName to newly publish version
     * and then change the Provisioned Concurrency it will fail with the following error:
     *
     * Alias with weights cannot be used with Provisioned Concurrency.
     *
     * So to fix that issue we added the sleep and some logic to only run
     * the sleep when it's needed.
     *
     */
    private void checkPublishBeforeRouting  (StageExecution stage) throws InterruptedException {
        List<StageExecution> stages = stage.getExecution().getStages().stream()
                .filter(s -> s.getType().equals("Aws.LambdaDeploymentStage")).collect(Collectors.toList());
        String currentRefId = stage.getRefId();
        String currentFunctionName = (String) stage.getContext().get("functionName");

        boolean isPublish = false;
        for (StageExecution s : stages) {
            String deploymentStageRefId = s.getRefId();
            if (s.getContext().get("functionName").equals(currentFunctionName)) {
                if (Integer.parseInt(deploymentStageRefId) < Integer.parseInt(currentRefId)
                        && !s.getStatus().equals(ExecutionStatus.SKIPPED)) {
                    isPublish = s.getContext().get("publish").equals(true);
                    break;
                }
            }
        }

        if (stage.getType().equals("Aws.LambdaTrafficRoutingStage") && isPublish) {
            logger.info("Waiting 3 minutes because previous stages did a publish");
            Thread.sleep(180000);
        }
    }
}