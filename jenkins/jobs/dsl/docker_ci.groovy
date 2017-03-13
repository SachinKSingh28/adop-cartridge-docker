// Variables
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"
def dockerfileGitRepo = "adop-cartridge-docker-reference"
def dockerfileGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + dockerfileGitRepo

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
        stringParam("IMAGE_REPO",dockerfileGitUrl,"Repository location of your Dockerfile")
        stringParam("IMAGE_TAG",'tomcat8',"Enter a unique string to tag your images (Note: Upper case chararacters are not allowed)")
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
    scm {
        git {
            remote {
                url('${IMAGE_REPO}')
                credentials("pluggable-scm")
            }
            branch("*/master")
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    label("docker")
    triggers {
        gerrit {
            events {
                refUpdated()
            }
            project(projectFolderName + '/' + dockerfileGitRepo, 'plain:master')
            configure { node ->
                node / serverName("ADOP Gerrit")
            }
        }
    }
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
            |docker build -t ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${BUILD_NUMBER} ${WORKSPACE}/.'''.stripMargin())

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
            |docker login -u ${DOCKERHUB_USERNAME} -p ${DOCKERHUB_PASSWORD} -e devops@adop.com
            |docker push ${DOCKERHUB_USERNAME}/${IMAGE_TAG}:${BUILD_NUMBER}'''.stripMargin())
    }
}
