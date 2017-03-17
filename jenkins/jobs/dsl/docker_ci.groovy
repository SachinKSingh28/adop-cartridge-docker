import pluggable.scm.*

SCMProvider scmProvider = SCMProviderHandler.getScmProvider("${SCM_PROVIDER_ID}", binding.variables)

//Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"
def projectScmNamespace = "${SCM_NAMESPACE}"

// Variables
def dockerfileGitRepo = "adop-cartridge-docker-reference"

// Jobs
def dockerci = freeStyleJob(projectFolderName + "/Docker_CI")

dockerci.with {
    description("Description")
    parameters {
        credentialsParam("DOCKER_LOGIN") {
            type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
            required()
            defaultValue('docker-credentials')
            description('Dockerhub username and password. Please make sure the credentials are added with ID "docker-credentials"')
        }
        stringParam("SCM_REPO",dockerfileGitRepo,"Repository location of your Dockerfile")
        stringParam("IMAGE_TAG",'tomcat8',"Enter a string to tag your images (Note: Upper case characters are not allowed) e.g. johnsmith/dockerimage:tagnumber for dockerhub or if pushing to aws aws_account_id.dkr.ecr.region.amazonaws.com/my-web-app")
        stringParam("CLAIR_DB",'',"URI for the Clair PostgreSQL database in the format postgresql://postgres:password@postgres:5432?sslmode=disable (ignore parameter as it is currently unsupported)")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
            usernamePassword("DOCKERHUB_USERNAME", "DOCKERHUB_PASSWORD", '${DOCKER_LOGIN}')
        }
    }
    scm scmProvider.get(projectScmNamespace, '${SCM_REPO}', "*/master", "adop-jenkins-master", null)
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    label("docker")
    triggers scmProvider.trigger(projectScmNamespace, '${SCM_REPO}', "master")
    steps {
        shell('''#!/bin/bash
            |set +x
            |echo "Pull the Dockerfile out of Git, ready for us to test and if successful, release via the pipeline."
            |
            |# Convert tag name to lowercase letters if any uppercase letters are present since they are not allowed by Docker
            |echo TAG=$(echo "$IMAGE_TAG" | awk '{print tolower($0)}') > image.properties
            |
            |# Rename dockerfile so that we can build other containers to run the lint/bdd on this source dockerfile
            |mv Dockerfile Dockerfile.source'''.stripMargin())
        
        environmentVariables {
            propertiesFile('image.properties')
        }
        shell('''#!/bin/bash
            |echo "Run dockerlint test on Dockerfile: https://github.com/RedCoolBeans/dockerlint"
            |MASTER_NAME=$(echo ${JENKINS_URL} | awk -F/ '{print $3}')
            |# Docker test wrapper image Dockerfile definition
            |mkdir -p tmp
            |echo '
            |FROM redcoolbeans/dockerlint:0.2.0
            |COPY Dockerfile.source /Dockerfile
            |'> tmp/Dockerfile.lintwrapper
            |
            |# Temporary docker file to build lint
            |cp tmp/Dockerfile.lintwrapper Dockerfile
            |
            |random=$(date +"%s")
            |
            |# Remove Debris If Any
            |#if [[ "$(docker images -q ${MASTER_NAME} 2> /dev/null)" != "" ]]; then
            |#  docker rmi -f "${MASTER_NAME}"
            |#fi
            |
            |# Create test wrapper image: dockerlint as a base, add Dockerfile on top
            |docker build -t "${MASTER_NAME}-${random}" .
            |
            |# Run Linting
            |docker run --rm "${MASTER_NAME}-${random}" > "${WORKSPACE}/${JOB_NAME##*/}.out"
            |
            |# Clean-up
            |docker rmi -f "${MASTER_NAME}-${random}"
            |
            |if ! grep "Dockerfile is OK" ${WORKSPACE}/${JOB_NAME##*/}.out ; then
            | echo "Dockerfile does not satisfy Dockerlint static code analysis"
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            | exit 1
            |else
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            |fi'''.stripMargin())

        shell('''#!/bin/bash
            |echo "Building the docker image locally..."
            |docker build --no-cache -t ${TAG}:${BUILD_NUMBER} - < Dockerfile.source'''.stripMargin())

        shell('''#!/bin/bash
            |echo "[INFO] TEST: Clair Testing Step"
            |echo "THIS STEP NEEDS TO BE UPDATED ONCE ACCESS TO A PRODUCTION CLAIR DATABASE IS AVAILABLE"
            |
            |if [ -z ${CLAIR_DB} ]; then
            | echo "WARNING: You have not provided the endpoints for a Clair database, moving on for now..."
            |else
            | # Set up Clair as a docker container
            | echo "Clair database endpoint: ${CLAIR_DB}"
            | mkdir /tmp/clair_config
            | curl -L https://raw.githubusercontent.com/coreos/clair/master/config.example.yaml -o /tmp/clair_config/config.yaml
            | # Add the URI for your postgres database
            | sed -i'' -e "s|options: |options: ${CLAIR_DB}|g" /tmp/clair_config/config.yaml
            | docker run -d -p 6060-6061:6060-6061 -v /tmp/clair_config:/config quay.io/coreos/clair -config=/config/config.yaml
            | # INSERT STEPS HERE TO RUN VULNERABILITY ANALYSIS ON IMAGE USING CLAIR API
            |fi'''.stripMargin())

        shell('''#!/bin/bash
            |echo "[INFO] TEST: BDD Testing Step"
            |MASTER_NAME=$(echo ${JENKINS_URL} | awk -F/ '{print $3}')
            |# Docker Test Wrapper Image
            |mkdir -p tmp
            |echo '
            |FROM luismsousa/docker-security-test
            |COPY Dockerfile.source /dockerdir/Dockerfile
            |'> tmp/Dockerfile.bddwrapper
            |
            |# Temporary docker file to build lint
            |cp tmp/Dockerfile.bddwrapper Dockerfile
            |
            |random=$(date +"%s")
            |
            |# Remove Debris If Any
            |#if [[ "$(docker images -q ${MASTER_NAME} 2> /dev/null)" != "" ]]; then
            |#  docker rmi -f "${MASTER_NAME}-${random}"
            |#fi
            |
            |# Create test wrapper image: security test as a base, add Dockerfile on top
            |docker build -t "${MASTER_NAME}-${random}" .
            |
            |# Run Security Test
            |set +e
            |docker run --rm -v "/var/run/docker.sock:/var/run/docker.sock" "${MASTER_NAME}-${random}" rake CUCUMBER_OPTS='features --format json --guess -o /dev/stdout' > "${WORKSPACE}/cucumber.json"
            |set -e
            |
            |# Clean-up
            |docker rmi -f "${MASTER_NAME}-${random}"
            |docker rm -f $(docker ps -a -q --filter 'name=container-to-delete')
            |'''.stripMargin())

        shell('''#!/bin/bash
            |set +x
            |echo "Pushing docker image to container registry"
            |if [[ "${TAG}" == *"amazonaws.com"* ]]; then
            | export AWS_ACCESS_KEY_ID=${DOCKERHUB_USERNAME}
            | export AWS_SECRET_ACCESS_KEY=${DOCKERHUB_PASSWORD}
            | export AWS_DEFAULT_REGION="${TAG#*.*.*.}"
            | export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION%%.*}"
            | set +e
            | aws --version || (curl "https://s3.amazonaws.com/aws-cli/awscli-bundle.zip" -o "awscli-bundle.zip" && \\
            | 	unzip awscli-bundle.zip && \\
            |    ./awscli-bundle/install -b ./aws \\
            | 	)
            | set -e
            | ECR_DOCKER_LOGIN=`./aws ecr get-login`
            | ${ECR_DOCKER_LOGIN}
            |elif [[ $(grep -o "/" <<< "$TAG" | wc -l) -eq 2 ]]; then
            | export DOCKERHUB_URL=${TAG%%/*}
            | docker login -u ${DOCKERHUB_USERNAME} -p ${DOCKERHUB_PASSWORD} ${DOCKERHUB_URL}
            |else
            | docker login -u ${DOCKERHUB_USERNAME} -p ${DOCKERHUB_PASSWORD}
            |fi
            |set -x
            |
            |# Push image to repository
            |docker push ${TAG}:${BUILD_NUMBER}
            |
            |# Clean up
            |docker rmi ${TAG}:${BUILD_NUMBER}'''.stripMargin())
        configure { myProject ->
            myProject / 'publishers' << 'net.masterthought.jenkins.CucumberReportPublisher'(plugin: 'cucumber-reports@0.1.0') {
               jsonReportDirectory("")
               pluginUrlPath("")
               fileIncludePattern("cucumber.json")
               fileExcludePattern("")
               skippedFails("false")
               pendingFails("false")
               undefinedFails("false")
               missingFails("false")
               noFlashCharts("false")
               ignoreFailedTests("false")
               parallelTesting("false")
           }
        }
    }
}
