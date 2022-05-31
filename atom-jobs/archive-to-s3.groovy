node("delivery") {
    container("delivery") {
        def ws = pwd()
        def user = sh(returnStdout: true, script: "whoami").trim()

        sh "find . -maxdepth 1 ! -path . -exec rm -rf {} +"

        stage("prepare") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "${ws}"
            println "${user}"
        }

        stage("download") {
            sh """
            wget -q ${SOURCE_URL}
            """
        }

        stage("upload to s3") {
            def target = "${DEST_NAME}"
            sh """
    export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
    upload.py ${target}  ${target}
    aws s3 cp ${target} s3://download.pingcap.org/${target} --acl public-read
    echo "upload ${target} successed!\n[########download path#########]: https://download.pingcap.org/${target}"
    """
        }
    }
}