# workspace-manager-clienttests
This Gradle project contains Test Runner tests written with the Workspace Manager Service client library.

The Test Runner library [GitHub repository](https://github.com/DataBiosphere/terra-test-runner) has documentation for
how to write and execute tests.

#### Run a test
The workspace manager tests require the appropriate service account keys to be available in the `rendered/` folder.

Run the following command from the `workspace-manager-clienttests` directory to retrieve the required keys.
Note: `render-config.sh` assumes a valid `.vault-token` in your `$HOME` directory. Please refer to the following document on how to generate a `vault-token`
 
 https://github.com/broadinstitute/dsde-toolbox#authenticating-to-vault 

```
./render-config.sh
```

The test runner task `runTest` can be used to launch tests in two different modes
* Run individual test
* Run a test suite

#### Run individual test
To run an individual test case, specify the path of the test configuration under the `configs` directory as shown in the following examples.
Developers can incorporate these individual test scenarios as use cases in their test workflows.
Please refer to the GitHub Action workflow use cases in the `Run a test suite` section below.

Integration Test
```
./gradlew  runTest --args="configs/integration/BasicAuthenticated.json /tmp/TR"
```

Perf Test
```
./gradlew  runTest --args="configs/perf/BasicAuthenticated.json /tmp/TR"
```

#### Run a test suite
To run a test suite, specify the path of the test configuration under the `suites` directory as shown in the following example.
NB: The `BasicIntegration.json` and `BasicNightlyPerf.json` are sample test suite configurations for Integration and Perf tests.
See `test-runner-integration.yml` and `test-runner-nightly-perf.yml` in `.github/workflows` for use cases.

Integration Test
```
./gradlew  runTest --args="suites/BasicIntegration.json /tmp/TR"
```

Perf Test
```
./gradlew  runTest --args="suites/BasicPerf.json /tmp/TR"
```
#### Upload test results to Google Bucket
To upload Test Runner results to a Google Bucket.
The bucket location is specified as a parameter in an upload config file located in the `resources/uploadlists` directory

The Gradle `uploadResults` task below archive the test results located in `/tmp/TR` to the bucket specified in config file `CompressDirectoryToBucket.json`.

```
./gradlew uploadResults --args="CompressDirectoryToBucket.json /tmp/TR"
```

The default server that the test will run against is specified in the test config file.
To override the default server, set an environment variable as in the following example.
```
export TEST_RUNNER_SERVER_SPECIFICATION_FILE="workspace-dev.json" 
./gradlew  runTest --args="configs/integration/BasicAuthenticated.json /tmp/TR"
```

#### SA keys from Vault
Each service account JSON file in the resources/serviceaccounts directory of this project specifies a default file
path for the client secret file. This default path should match where the render-config.sh script puts the secret.

#### Test runner SA keys
The test runner SA key is necessary for uploading test results to Google Buckets and BigQuery. 
Each environment has its own test runner SA key with the right permissions for uploading test results.
The provisioning of the test runner SA keys will be done with `Terraform` in collaboration with `devOps`.
Additional `k8s` permission will allow test runner to manipulate a cluster for resiliency tests.

Here's the vault paths of various test runner SA keys.

Static environments (`<env> = { alpha, dev, perf, qa, staging }`)
One test runner SA key for each static environment.
`secret/dsde/terra/kernel/<env>/common/testrunner-sa`

Preview environments
One common test runner SA key for all preview environments.
`secret/dsde/terra/kernel/integration/preview/testrunner-sa`

#### Google Buckets for uploading test runner results
Each Terra service should have its own bucket for uploading test runner results.

For example,
`gs://broad-dsde-dev-workspace-manager-testrunner-results`

`broad-dsde-dev` identifies the project name or target environment
`workspace-manager` identifies the target service as `terra-workspace-manager`

NB: A Google Bucket name has a limit of up to 63 characters.

#### Use a local Workspace Manager client JAR file
The version of the Workspace Manager client JAR file is specified in the build.gradle file in this sub-project. This JAR file is
fetched from the Broad Institute Maven repository. You can override this to use a local version of the Workspace Manager client
JAR file by specifying a Gradle project property, either with a command line argument

`./gradlew -Pworkspacemanagerclientjar=~/terra-workspace-manager/workspace-manager-client/build/libs/workspace-manager-client-0.5.0-SNAPSHOT.jar runTest --args="configs/integration/BasicAuthenticated.json"

or an environment variable.

```
export ORG_GRADLE_PROJECT_workspacemanagerclientjar=~/terra-workspace-manager/workspace-manager-client/build/libs/workspace-manager-client-0.5.0-SNAPSHOT.jar
./gradlew runTest --args="configs/integration/BasicAuthenticated.json /tmp/TestRunnerResults"
```

This is useful for debugging or testing local server code changes that affect the generated client library (e.g. new API
endpoint). You can generate the Workspace Manager client library with the Gradle assemble task of the `workspace-manager-client` sub-project.
Please refer to `test-runner-integration.yml` for an example use case.

```
cd ~/terra-workspace-manager/workspace-manager-client
../gradlew clean assemble
ls -la ./build/libs/*jar
```