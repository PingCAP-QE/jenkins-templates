/*
* @GIT_BRANCH(string:repo branch, Required)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
*/

properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'GIT_BRANCH',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                )
        ])
])

def get_sha(repo) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${GIT_BRANCH} -s=${FILE_SERVER_URL}").trim()
}


def release_one(repo) {
    def sha1 =  get_sha(repo)
    if (sha1.length() == 40) {
        println "valid sha1: ${sha1}"
    } else {
        println "invalid sha1: ${sha1}"
        currentBuild.result = "FAILURE"
        throw new Exception("Invalid sha1: ${sha1}, Throw to stop pipeline")
    }
    def binary = "builds/pingcap/${repo}/test/${sha1}/linux-arm64/${repo}.tar.gz"
    def paramsBuild = [
        string(name: "ARCH", value: "arm64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "GIT_HASH", value: sha1),
        string(name: "TARGET_BRANCH", value: GIT_BRANCH),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}


def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-cd"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest", ttyEnabled: true,
                        resourceRequestCpu: '200m', resourceRequestMemory: '1Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]     
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

stage ("release") {
    run_with_pod {
        container("golang") {
            releaseRepos = ["tidb","tidb-test","tikv","pd"]
            builds = [:]
            for (item in releaseRepos) {
                def product = "${item}"
                builds["build ${item}"] = {
                    release_one(product)
                }
            }
            parallel builds
        }
    }
}