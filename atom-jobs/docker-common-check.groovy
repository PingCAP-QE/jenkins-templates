/*
* @INPUT_BINARYS(string:binary url on fileserver, transfer througth atom jobs, Required)
* @REPO(string:repo name,eg tidb, Required)
* @PRODUCT(string:product name,eg tidb-ctl,if not set,default was the same as repo name, Optional)
* @ARCH(enumerate:arm64,amd64,Required)
* @OS(enumerate:linux,darwin,Required)
* @DOCKERFILE(string: url to download dockerfile, Optional)
* @RELEASE_TAG(string:for release workflow,what tag to release,Optional)
* @RELEASE_DOCKER_IMAGES(string:image to release seprate by comma, Required)
*/

properties([
        parameters([
                choice(
                        choices: ['arm64', 'amd64'],
                        name: 'ARCH'
                ),
                choice(
                        choices: ['linux', 'darwin'],
                        name: 'OS'
                ),
                string(
                        defaultValue: '',
                        name: 'INPUT_BINARYS',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PRODUCT',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'DOCKERFILE',
                        trim: true
                ),

                string(
                        defaultValue: '',
                        name: 'RELEASE_DOCKER_IMAGES',
                        trim: true
                ),

                string(
                        defaultValue: '',
                        name: 'GIT_BRANCH',
                        trim: true
                )
        ])
])

env.DOCKER_HOST = "tcp://localhost:2375"
env.DOCKER_REGISTRY = "docker.io"

if (params.PRODUCT.length() <= 1) {
    PRODUCT = REPO
}

// download binarys
binarys = params.INPUT_BINARYS.split(",")

def download() {
    for (item in binarys) {
        sh "curl ${FILE_SERVER_URL}/download/${item} | tar xz"
    }
}

// 构建出的镜像名称
imagePlaceHolder = UUID.randomUUID().toString()
// 使用非默认脚本构建镜像，构建出的镜像名称需要在下面定义 
if (PRODUCT == "tics" || PRODUCT == "tiflash") {
    if (RELEASE_TAG.length() > 1) {
        imagePlaceHolder = "hub.pingcap.net/tiflash/tiflash-server-centos7"
    } else {
        imagePlaceHolder = "hub.pingcap.net/tiflash/tiflash-ci-centos7"
    }
}

// 定义非默认的构建镜像脚本
buildImgagesh = [:]
buildImgagesh["tics"] = """
curl -o Dockerfile ${DOCKERFILE}
if [[ "${RELEASE_TAG}" == "" ]]; then
    # No release tag, the image may be used in testings
    docker build -t ${imagePlaceHolder} . --build-arg INSTALL_MYSQL=1
else
    # Release tag provided, do not install test utils
    docker build -t ${imagePlaceHolder} . --build-arg INSTALL_MYSQL=0
fi
"""

buildImgagesh["tiflash"] = """
curl -o Dockerfile ${DOCKERFILE}
if [[ "${RELEASE_TAG}" == "" ]]; then
    # No release tag, the image may be used in testings
    docker build -t ${imagePlaceHolder} . --build-arg INSTALL_MYSQL=1
else
    # Release tag provided, do not install test utils
    docker build -t ${imagePlaceHolder} . --build-arg INSTALL_MYSQL=0
fi
"""


buildImgagesh["monitoring"] = """
docker build -t ${imagePlaceHolder} .
"""

buildImgagesh["tiem"] = """
cp /usr/local/go/lib/time/zoneinfo.zip ./
docker build  -t ${imagePlaceHolder} .
"""

buildImgagesh["tidb"] = """
cp /usr/local/go/lib/time/zoneinfo.zip ./
rm -rf tidb-server
cp bin/tidb-server ./
if [[ -f "bin/whitelist-1.so" ]]; then
    cp bin/whitelist-1.so ./
    echo "plugin file existed: whitelist-1.so"
fi
if [[ -f "bin/audit-1.so" ]]; then
    cp bin/audit-1.so ./
    echo "plugin file existed: audit-1.so"
fi
curl -o Dockerfile ${DOCKERFILE}
docker build  -t ${imagePlaceHolder} .
"""


def build_image() {
    // 如果构建脚本被定义了，使用定义的构建脚本
    if (buildImgagesh.containsKey(PRODUCT)) {
        sh buildImgagesh[PRODUCT]
    } else { // 如果没定义，使用默认构建脚本
        sh """
        rm -rf tmp-docker-build
        mkdir -p tmp-docker-build
        cd tmp-docker-build
        cp /usr/local/go/lib/time/zoneinfo.zip ./
        cp ../bin/* ./
        curl -o Dockerfile ${DOCKERFILE}
        docker build  -t ${imagePlaceHolder} .
        """
    }
}


def nodeLabel = "delivery"
def containerLabel = "delivery"
if (params.ARCH == "arm64") {
    nodeLabel = "arm"
    containerLabel = ""
}

images = params.RELEASE_DOCKER_IMAGES.split(",")

/**
 * checklist
 * hash
 * release_tag
 * not check version because of not docker tag
 */
def local_check() {
    comp_to_binary = [
            "pd"            : ["/pd-server"],
            "tikv"          : ["/tikv-server"],
            "tidb"          : ["/tidb-server"],
            "br"            : ["/br"],
            "dumpling"      : ["/dumpling"],
            "tidb-binlog"   : ["/pump", "/drainer"],
            "ticdc"         : ["/cdc"],
            "tidb-lightning": ["/tidb-lightning", "/tikv-importer", "/br"],
            "dm"            : ["/dm-master", "/dm-worker", "/dmctl"],
    ]

    repo_list=[
            "pd"            : "pd",
            "tikv"          : "tikv",
            "tidb"          : "tidb",
            "br"            : "tidb",
            "dumpling"      : "dumpling",
            "tidb-binlog"   : "tidb-binlog",
            "ticdc"         : "ticdc",
            "tidb-lightning": "tidb",
            "dm"            : "dm",
    ]
    def product = params.PRODUCT
    def release_tag_expect = params.RELEASE_TAG.replaceAll('v', '')
    def entry = comp_to_binary[product]
    if (entry == null) {
        println("product:%s not in local check list", product)
    } else {
        def commit_expect = get_sha(repo_list[params.REPO], params.GIT_BRANCH)
        for (item in images) {
            if (release_tag_expect >= "5.2.0") {
                comp_to_binary["tidb-lightning"] = ["/tidb-lightning", "/br"]
            }
            for (binary in comp_to_binary[product]) {
                sh """
echo ${binary}               
cd bin/
if [ ${product} == 'ticdc' ]
then 
    .${binary} version 2>&1 | tee info.txt
else
    .${binary} -V 2>&1 | tee info.txt
fi 

commit_actual=`cat info.txt | grep -e 'Git Commit Hash' -e 'Git commit hash'| awk -F ':' '{print \$2}'`
release_tag_actual=`cat info.txt | grep -e 'Release Version' -e 'Release version' | awk -F ':' '{print \$2}'`
release_tag_actual_exclude_v=\${release_tag_actual/v/}
if [ ${commit_expect} == \$commit_actual ] && [ ${release_tag_expect} == \$release_tag_actual_exclude_v ]
then
    echo "pass local check! commit and release_tag check successful!"
else
    echo "fail local check!"
    echo "commit_expect:${commit_expect};commit_actual:\$commit_actual" 
    echo "release_tag_expect:${release_tag_expect};release_tag_actual:\$release_tag_actual_exclude_v" 
    exit 1 
fi
"""
            }

        }
    }
}

def get_sha(repo, branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

def release_images() {
    for (item in images) {
        if (item.startsWith("pingcap/")) {
            def harbor_tmp_image_name = "hub.pingcap.net/image-sync/" + item

            // This is for debugging
            // Debug ENV
            // def sync_dest_image_name = item.replace("pingcap/", "tidbdev/")
            // Prod ENV
            def sync_dest_image_name = item
            // End debugging

            docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                sh """
               # Push to Internal Harbor First, then sync to DockerHub 
               # pingcap/tidb:v5.2.3 will be pushed to hub.pingcap.net/image-sync/pingcap/tidb:v5.2.3
               docker tag ${imagePlaceHolder} ${harbor_tmp_image_name}
               docker push ${harbor_tmp_image_name}
               """
            }

            sync_image_params = [
                    string(name: 'triggered_by_upstream_ci', value: "docker-common-nova"),
                    string(name: 'SOURCE_IMAGE', value: harbor_tmp_image_name),
                    string(name: 'TARGET_IMAGE', value: sync_dest_image_name),
            ]
            build(job: "jenkins-image-syncer", parameters: sync_image_params, wait: true, propagate: true)
        }
        if (item.startsWith("hub.pingcap.net/")) {
            docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                sh """
               docker tag ${imagePlaceHolder} ${item}
               docker push ${item}
               """
            }
        }
        if (item.startsWith("uhub.service.ucloud.cn/")) {
            docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                sh """
               docker tag ${imagePlaceHolder} ${item}
               docker push ${item}
               """
            }
        }
    }
    // 清理镜像
    sh "docker rmi ${imagePlaceHolder} || true"
}

def release() {
    stage("Prepare") {
        deleteDir()
    }
    stage("Download") {
        deleteDir()
        download()
    }
//    只校验 release 分支
    if (params.GIT_BRANCH.startsWith("release-")) {
        stage("local check") {
            local_check()
        }
    }
//    stage("Build") {
//        build_image()
//    }
//
//    stage("Push image") {
//        release_images()
//    }
}

stage("Build & Release ${PRODUCT} image") {
    node(nodeLabel) {
        if (containerLabel != "") {
            container(containerLabel) {
                release()
            }
        } else {
            release()
        }
    }
}