/*
* @ARCH
* @PRODUCT
* @COMMIT
* @TYPE
* @EDITION
* @RELEASE_TAG
*/
properties([
        parameters([
                choice(
                        choices: ['linux-arm64', 'linux-amd64', 'darwin-arm64', 'darwin-amd64'],
                        name: 'ARCH'
                ),
                choice(
                        choices: ['tidb', 'tikv', 'pd', 'tiflash', 'br', 'tidb-binlog', 'tidb-lightning', 'ticdc', 'dumpling'],
                        name: 'PRODUCT',
                ),
                string(
                        defaultValue: '',
                        name: 'COMMIT',
                        trim: true
                ),
                choice(
                        choices: ['docker-dockerhub', 'docker-ucloud', 'tiup-online', 'tiup-offline'],
                        name: 'TYPE'
                ),
                choice(
                        choices: ['community', 'enterprise'],
                        name: 'EDITION'
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
        ])
])
def product = params.PRODUCT
def type = params.TYPE
def arch = params.ARCH
def edition = params.EDITION
def release_tag = params.RELEASE_TAG
def commit = params.COMMIT

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
                        python3 main_atom.py image -c $it --local false --registry ${registry} ${release_tag}.json ${release_tag} ${edition} 
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
                sh "python3 main_atom.py tiupoffline --arch ${arch} ${release_tag}.json ${release_tag} ${edition}"
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
                        sh "python3 main_atom.py tiupoffline --arch ${arch} ${release_tag}.json ${release_tag} ${edition}"
                    }
                }
            }
        }
    }
}

def check_online_tiup = { products, edition_param, arch_param ->
    mapping_arch_label = [
            'darwin-amd64': 'mac',
            'darwin-arm64': 'mac',
            'linux-arm64' : 'arm'
    ]
    if (edition_param == 'community') {
//        TODO:darwin-arm64 not verify now
        if (arch_param == "darwin-amd64" || arch_param == "linux-arm64" || arch_param == "darwin-arm64") {
            node(mapping_arch_label[arch_param]) {
                unstash 'qa'
                dir("qa/release-checker/checker") {
                    products.each {
                        sh "python3 main_atom.py tiuponline -c $it ${release_tag}.json ${release_tag}"
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
                            python3 main_atom.py tiuponline -c $it ${release_tag}.json ${release_tag}
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
               cat > ${release_tag}.json << __EOF__
{
  "${product}_commit":"${commit}"
}
__EOF__
                        """
            stash includes: "${release_tag}.json", name: "release.json"
            dir("qa") {
                checkout scm: [$class           : 'GitSCM',
                               branches         : [[name: "feature/cd0411"]],
                               extensions       : [[$class: 'LocalBranch']],
                               userRemoteConfigs: [[credentialsId: 'heibaijian', url: 'https://github.com/heibaijian/jenkins-templates.git']]]

            }
            sh "cp ${release_tag}.json qa/release-checker/checker"
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
        if (type == 'tiup-online') {
//            DONE：1、增加arch参数
            check_online_tiup([product], edition, arch)
        } else {
            check_offline_tiup(arch, edition)
        }
    }
}