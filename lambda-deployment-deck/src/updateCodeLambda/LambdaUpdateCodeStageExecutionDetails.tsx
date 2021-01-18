// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import React from 'react';

import {
  ExecutionDetailsSection,
  IExecutionDetailsSectionProps,
  StageFailureMessage,
} from '@spinnaker/core';

export function LambdaUpdateCodeExecutionDetails(props: IExecutionDetailsSectionProps) {
  const { stage, current, name } = props;
  return (
    <ExecutionDetailsSection name={name} current={current}>
      <StageFailureMessage stage={stage} message={stage.outputs.failureMessage} />
      <div>
        <p> <b> Function Name: </b> {stage.outputs.functionName ? stage.outputs.functionName : "N/A"} </p>
        <p> <b> Function ARN: </b> {stage.outputs.functionARN ? stage.outputs.functionARN : "N/A"} </p>
      </div>
    </ExecutionDetailsSection>
  );
}

export namespace LambdaUpdateCodeExecutionDetails {
  export const title = 'Lambda Update Code Stage';
}
