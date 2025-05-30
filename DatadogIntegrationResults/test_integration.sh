#!/bin/bash

echo "Building core module..."
cd /home/ubuntu/repos/EvoMaster-fork
mvn -f core/pom.xml clean compile -DskipTests

echo "Building features-service SUT..."
mvn -f SUTS/features-service/pom.xml clean package -DskipTests

echo "Starting features-service SUT with Datadog agent..."
cd SUTS/features-service
java -Devomaster.datadog.enabled=true \
     -Devomaster.datadog.service.name=features-service \
     -jar target/features-service-evomaster-runner.jar &
SUT_PID=$!

echo "Waiting for SUT to start..."
sleep 10

echo "Running EvoMaster with Datadog integration..."
cd /home/ubuntu/repos/EvoMaster-fork
java -jar core/target/evomaster.jar \
     --algorithm=DATADOG_ENHANCED \
     --enableDatadogIntegration=true \
     --datadogApiKey=5c729a3e675a02ad9bf794c74b2b7d4c \
     --datadogAppKey=c6a4485efe69378f2088090b17791a0fc46cff89 \
     --datadogApiUrl=https://us5.datadoghq.com \
     --datadogServiceName=features-service \
     --outputFolder=DatadogIntegrationResults/tests \
     --maxTime=60s \
     --seed=42 \
     --sutControllerPort=40100 \
     --outputFormat=JAVA_JUNIT_4 \
     --testSuiteFileName=DatadogIntegrationTest

echo "Capturing logs..."
mkdir -p DatadogIntegrationResults/logs
cp core/target/logs/evomaster.log DatadogIntegrationResults/logs/

echo "Stopping SUT..."
kill $SUT_PID

echo "Test completed."
