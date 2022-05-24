import java.text.SimpleDateFormat

properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'AMD64_IMAGE',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'ARM64_IMAGE',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'MULTI_ARCH_IMAGE',
                        trim: true,
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'IF_ENTERPRISE'
                ),
        ])
])

def date = new Date()
sdf = new SimpleDateFormat("yyyyMMdd")
day = sdf.format(date)

node("delivery") {
    container("delivery") {
        stage("build multi-arch") {
            withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                sh """
            docker login -u ${harborUser} -p ${harborPassword} hub.pingcap.net
            cat <<EOF > manifest.yaml
image: ${MULTI_ARCH_IMAGE}
manifests:
-
    image: ${ARM64_IMAGE}
    platform:
    architecture: arm64
    os: linux
-
    image: ${AMD64_IMAGE}
    platform:
    architecture: amd64
    os: linux

EOF
            cat manifest.yaml
            curl -o manifest-tool ${FILE_SERVER_URL}/download/cicd/tools/manifest-tool-linux-amd64
            chmod +x manifest-tool
            ./manifest-tool push from-spec manifest.yaml
            """

            }
            archiveArtifacts artifacts: "manifest.yaml", fingerprint: true
        }
        if (IF_ENTERPRISE == "false") {
            stage("sync community image to dockerhub") {
                source_image = params.MULTI_ARCH_IMAGE
                dest_image = source_image.replace("hub.pingcap.net/qa", "pingcap")
                def default_params = [
                        string(name: 'SOURCE_IMAGE', value: source_image),
                        string(name: 'TARGET_IMAGE', value: dest_image),
                ]
                build(job: "jenkins-image-syncer",
                        parameters: default_params,
                        wait: true)

            }
        } else {
            stage("sync enterprise image to cloud") {
                def source_image = params.MULTI_ARCH_IMAGE
                // 命名规范：
                //- vX.Y.Z-yyyymmdd，举例：v6.1.0-20220524
                //- 特例：tidb-monitor-initializer 镜像格式要求，vX.Y.Z，举例：v6.1.0 （每次覆盖即可，这个问题是DBaaS上历史问题）
                def dest_image = source_image.replace("hub.pingcap.net/qa", "gcr.io/pingcap-public/dbaas")
                if (!dest_image.contains("tidb-monitor-initializer-enterprise")) {
                    dest_image = dest_image + "-" + day
                }

                def default_params = [
                        string(name: 'SOURCE_IMAGE', value: source_image),
                        string(name: 'TARGET_IMAGE', value: dest_image),
                ]
                build(job: "jenkins-image-syncer",
                        parameters: default_params,
                        wait: true)

            }
        }

    }
    println "multi arch image: ${MULTI_ARCH_IMAGE}"
}