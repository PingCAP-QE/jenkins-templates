/*
* @TIDB_VERSION
* @TIKV_VERSION
* @PD_VERSION
* @TIFLASH_VERSION
* @BR_VERSION
* @BINLOG_VERSION
* @LIGHTNING_VERSION
* @IMPORTER_VERSION
* @TOOLS_VERSION
* @CDC_VERSION
* @DUMPLING_VERSION
* @RELEASE_TAG
*/
properties([
        parameters([
                choice(
                        choices: ['arm64', 'amd64'],
                        name: 'ARCH'
                ),
                string(
                        defaultValue: '',
                        name: 'PRODUCT',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'EDITION',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'VERSION',
                        trim: true
                )
        ])
])
def product = params.PRODUCT
def type = params.TYPE
def arch = params.ARCH
def edition = params.EDITION

def task = "release-check-atom"
def check_image_registry = { products, edition_param, registry ->
    podTemplate(name: task, label: task, instanceCap: 5, idleMinutes: 120, containers: [
            containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true),
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/jenkins/release-checker:master', alwaysPullImage: true, envVars: [
                    envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
            ], ttyEnabled: true, command: 'cat'),
    ]) {
        node(task) {
            container("docker") {
                unstash 'qa'
                dir("qa/release-checker/checker") {
                    products.each {
                        sh """
                        python3 main.py image -c $it --registry ${registry} ${RELEASE_TAG}.json ${RELEASE_TAG} ${edition}
                     """
                    }
                }
            }
        }
    }
}


def check_offline_tiup = { arch_param, edition_param ->
    if (arch_param == "linux-arm64") {
        node("arm") {
            deleteDir()
            unstash 'qa'
            dir("qa/release-checker/checker") {
                sh "python3 main_atom.py tiup_offline --arch ${arch} ${RELEASE_TAG}.json ${RELEASE_TAG} ${edition}"
            }
        }
    } else {
        def imageName = "hub.pingcap.net/jenkins/release-checker:tiflash"
        def label = task + "-tiflash"
        podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 120, containers: [
                containerTemplate(name: 'main', image: imageName, alwaysPullImage: true,
                        ttyEnabled: true, command: 'cat'),
        ]) {
            node(label) {
                container("main") {
                    unstash 'qa'
                    dir("qa/release-checker/checker") {
                        sh "python3 main_atom.py pingcap --arch ${arch} ${RELEASE_TAG}.json ${RELEASE_TAG} ${edition}"
                    }
                }
            }
        }
    }
}

def check_online_tiup = { products, edition_param, arch_param ->
    if (edition_param == 'community') {
//        TODO:darwin-arm64 not verify now
        if (arch_param == "darwin-amd64" || arch_param == "linux-arm64" || arch_param == "darwin-arm64") {
            node(arch) {
                unstash 'qa'
                dir("qa/release-checker/checker") {
                    products.each {
                        sh "python3 main_atom.py tiup_online -c $it ${RELEASE_TAG}.json ${RELEASE_TAG}"
                    }
                }
            }
        } else {
            def imageName = "hub.pingcap.net/jenkins/release-checker:tiflash"
            arch_param = task + "-tiflash"
            podTemplate(name: arch_param, label: arch_param, instanceCap: 5, idleMinutes: 120, containers: [
                    containerTemplate(name: 'main', image: imageName, alwaysPullImage: true,
                            ttyEnabled: true, command: 'cat'),
            ]) {
                node(arch_param) {
                    container("main") {
                        unstash 'qa'
                        dir("qa/release-checker/checker") {
                            products.each {
                                sh """
                            python3 main_atom.py tiup_online -c $it ${RELEASE_TAG}.json ${RELEASE_TAG}
                            """
                            }
                        }
                    }
                }
            }
        }
    } else {
        println("tiup version error! expect community!")
    }
}

//TODO:记得修改分支，目前是测试分支
stage("prepare") {
    node('delivery') {
        container('delivery') {
            sh """
               cat > ${RELEASE_TAG}.json << __EOF__
{
  "${PRODUCT}_commit":"${COMMIT}"
}
__EOF__
                        """
            stash includes: "${RELEASE_TAG}.json", name: "release.json"
            dir("qa") {
                checkout scm: [$class           : 'GitSCM',
                               branches         : [[name: "feature/cd0411"]],
                               extensions       : [[$class: 'LocalBranch']],
                               userRemoteConfigs: [[credentialsId: 'heibaijian', url: 'https://github.com/heibaijian/jenkins-templates.git']]]

            }
            sh "cp ${RELEASE_TAG}.json qa/release-checker/checker"
            stash includes: "qa/**", name: "qa"
        }
    }
}


stage("verify") {
     if (type == 'docker-dockerhub') {
        check_image_registry([product], edition, "registry.hub.docker.com")
    } else if (type == 'docker-ucloud') {
        check_image_registry([product], edition, "uhub.service.ucloud.cn")
    } else {
//        tiup online
        if (type == 'tiup online') {
//            DONE：1、增加arch参数
            check_online_tiup([product], edition, arch)
        } else {
            check_offline_tiup(arch, edition)
        }
    }
}