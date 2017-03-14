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
        nodejs('ADOP NodeJS')
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
        shell('''set +x
            |echo "Pull the Dockerfile out of Git, ready for us to test and if successful, release via the pipeline."
            |
            |# Convert tag name to lowercase letters if any uppercase letters are present since they are not allowed by Docker
            |echo TAG=$(echo "$IMAGE_TAG" | awk '{print tolower($0)}')'''.stripMargin())

        shell('''echo "Run dockerlint test on Dockerfile: https://github.com/RedCoolBeans/dockerlint"
            |# Add your local node_modules bin to the path for this command
            |export PATH="./node_modules/.bin:$PATH"
            |npm install
            |
            |dockerlint "${WORKSPACE}/Dockerfile" > "${WORKSPACE}/${JOB_NAME##*/}.out"
            |
            |if ! grep "Dockerfile is OK" ${WORKSPACE}/${JOB_NAME##*/}.out ; then
            | echo "Dockerfile does not satisfy Dockerlint static code analysis"
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            | exit 1
            |else
            | cat ${WORKSPACE}/${JOB_NAME##*/}.out
            |fi'''.stripMargin())

        shell('''echo "Building the docker image locally..."
            |docker build -t ${IMAGE_TAG} ${WORKSPACE}/.'''.stripMargin())

        shell('''echo "THIS STEP NEEDS TO BE UPDATED ONCE ACCESS TO A PRODUCTION CLAIR DATABASE IS AVAILABLE"
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

        shell('''echo "DOCKER PUSH"
            |if [[ ${IMAGE_TAG} == *"amazonaws.com"* ]]; then
            | export AWS_ACCESS_KEY_ID=${DOCKERHUB_USERNAME}
            | export AWS_SECRET_ACCESS_KEY=${DOCKERHUB_PASSWORD}
            | export AWS_DEFAULT_REGION="${IMAGE_TAG#*.*.*.}"
            | export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION%%.*}"
            | ECR_DOCKER_LOGIN=`aws ecr get-login`
            | ${ECR_DOCKER_LOGIN}
            |else
            | docker login -u ${DOCKERHUB_USERNAME} -p ${DOCKERHUB_PASSWORD} -e devops@adop.com
            |fi
            docker push ${IMAGE_TAG}'''.stripMargin())
    }
}
