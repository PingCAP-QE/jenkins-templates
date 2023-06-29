properties([
    parameters([
        string(
            defaultValue: 'tidb',
            name: 'REPO',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'TASK_NAME',
            trim: true
        ),
        string(
            defaultValue: '',
            name: 'TRIGGER_EVENT',
            trim: true
        ),
        string(
        defaultValue: 'test_repot',
        name: 'TEST_REPORT_DIR',
        trim: true
        ),
    ])
])

tcmsHost = "https://tcms.pingcap.net/"

now = Calendar.getInstance().getTime().getTime()/1000
timestamp = now.intValue().toString()
triggerCI = httpRequest url: tcmsHost + "api/v1/dailyci/trigger", httpMode: 'POST'
ciResp = readJSON text: triggerCI.content
id = ciResp["id"].toString()
ciFinished = false
ciDuration = 0
while(!ciFinished) {
    sleep(300)
    ciDuration = ciDuration +300
    // ci breaks when timeout(23 hours)
    if (ciDuration > 115200) {
        echo "daily ci timeout reached, stop waiting task complete and collect result now."
        break
    }
    statusCI = httpRequest tcmsHost + "api/v1/dailyci/trigger/" + id
    statusResp = readJSON text: statusCI.content
    ciFinished = statusResp["finished"].toBoolean()
}


def run_with_pod(Closure body) {
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def label = "atom-tcms-${BUILD_NUMBER}"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                            
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


def response = httpRequest tcmsHost + "api/v1/plans/branchs"
def branches = readJSON text: response.content
for (b in branches) {
    branch= b.toString()
    if (branch == "main" ) {
	    run_with_pod {
	        container("golang"){
	            stage("branch: "+ branch + " daily ci result") {
	                sh """
	                wget -O ./${branch}.xml ${tcmsHost}api/v1/dailyci?id=${id}&branch=${branch}
                    mkdir -p ${TEST_REPORT_DIR}
                    cp ./${branch}.xml ${TEST_REPORT_DIR}
	                """
	                sleep(20)
	                sh """
                    cd ${REPORT_DIR}
	                wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_tcms/tiinsight-agent-tcms.py
	                python3 tiinsight-agent-tcms.py "${REPO}" ${branch} "empty" "${branch}.xml"

                    wget ${FILE_SERVER_URL}/download/rd-atom-agent/atom-tcms/agent-tcms.py
                    python3 agent-tcms.py ${branch} tcms.xml ${BUILD_URL}
                    """
                    ENV_TCMS_SUMMARY = sh(script: "cat tcms_summary.info", returnStdout: true).trim()
                    println ENV_TCMS_SUMMARY
                    currentBuild.description = "${ENV_TCMS_SUMMARY}"
	                junit testResults: "${TEST_REPORT_DIR}/${branch}.xml"
	            }
	        }
	    }  
    }
}