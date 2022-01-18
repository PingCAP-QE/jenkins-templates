import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import groovy.json.JsonBuilder

class Credential {
    String jenkinsID;
    String key;    
}

class Var {
    String value;
    String key;      
}

class Resource {
    String memory;
    String cpu;
}

class Resources {
    Resource limits;
    Resource requests;
}


class TaskSpec {
    Integer id;
    Integer pipelineID;
    String taskName;
    String checkerName;
    String pipelineName;
    String triggerEvent;
    String branch;
    String pullRequest;
    String commitID;
    String owner;
    String repo;
    String cacheCodeURL;
    String status;
    String jenkinsRunURL;
    String result;
    Integer retry;
    Integer timeout;
    Credential[] credentials;
    Var[] vars;
    String image;
    Resources resources;
    Map params;
}

class Triggers {
}

class Notify {
}


class PipelineSpec {
    Integer id;
    String pipelineName;
    String repo;
    String owner;
    String triggerEvent;
    String branch;
    String pullRequest;
    String commitID;
    String status;
    String jenkinsRunURL;
    String defaultRef;
    Triggers triggers;
    Notify  notify;
    TaskSpec[] tasks;
}

def loadPipelineConfig(fileURL) {
    ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory())
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false)
    yamlRequest = httpRequest url: fileURL, httpMode: 'GET'
    PipelineSpec pipelineSpec = objectMapper.readValue(yamlRequest.content, PipelineSpec.class)
    repoInfo = pipelineSpec.repo.split("/")
    if (repoInfo.length == 2){
        pipelineSpec.owner = repoInfo[0]
        pipelineSpec.repo = repoInfo[1]
    }
    return pipelineSpec
}


def createPipelineRun(PipelineSpec pipeline) {
    // create pipelinerun to tipipeline and get pipeline_id, task_id
    response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: new JsonBuilder(pipeline).toPrettyString(), url: "http://172.16.5.15:30792/pipelinerun", validResponseCodes: '200'
    ObjectMapper objectMapper = new ObjectMapper()
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false)
    PipelineSpec pipelineWithID = objectMapper.readValue(response.content, PipelineSpec.class)
    pipeline.id = pipelineWithID.id
    for (taskWithID in pipelineWithID.tasks) {
        for (task in pipeline.tasks) {
            if (taskWithID.taskName == task.taskName) {
                task.id = taskWithID.id
            }
        }
    }
    return pipeline
}
def updatePipelineRun(PipelineSpec pipeline) {
    // create pipelinerun to tipipeline and get pipeline_id, task_id
    response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'PUT', requestBody: new JsonBuilder(pipeline).toPrettyString(), url: "http://172.16.5.15:30792/pipelinerun", validResponseCodes: '200'
}


def triggerTask(taskName,params) {
    result = build(job: taskName, parameters: params, wait: true,propagate: false)

    if (result.getResult() != "SUCCESS" && taskName in ["atom-ut", "atom-gosec"]) {
        println("Detail: ${CI_JENKINS_BASE_URL}/blue/organizations/jenkins/${result.getFullProjectName()}/detail/${result.getFullProjectName()}/${result.getNumber().toString()}/tests")
    } else {
        println("Detail: ${CI_JENKINS_BASE_URL}/blue/organizations/jenkins/${result.getFullProjectName()}/detail/${result.getFullProjectName()}/${result.getNumber().toString()}/pipeline")
    }
    if (result.getDescription() != null && result.getDescription() != "") {
        println("task ${result.getResult()}: ${result.getDescription()}")
    } else {
        println("task ${result.getResult()}")
    }

    return result
}

def cacheCode(repo,commitID,branch,prID) {
    cacheCodeParams = [
        string(name: 'ORG_AND_REPO', value: repo),
        string(name: 'COMMIT_ID', value: commitID),
    ]
    if (branch != "" && branch != null ) {
        cacheCodeParams.push(string(name: 'BRANCH', value: branch))
    }
    if (prID != "" && prID != null ) {
        cacheCodeParams.push(string(name: 'PULL_ID', value: prID))
    }
    triggerTask("cache-code",cacheCodeParams)
}


def runPipeline(PipelineSpec pipeline, String triggerEvent, String branch, String commitID, String pullRequest) {
    try {
        pipeline.commitID = commitID
        pipeline.branch = branch
        pipeline.pullRequest = pullRequest
        pipeline.triggerEvent = triggerEvent
        pipeline.status = "created"
        pipeline.jenkinsRunURL = RUN_DISPLAY_URL
        pipeline = createPipelineRun(pipeline)
        cacheCode("${pipeline.owner}/${pipeline.repo}",commitID,branch,pullRequest)
        jobs = [:]
        for (task in pipeline.tasks) {
            def originTask = task
            jobs[task.taskName] = {
                def cacheCodeUrl = "${FILE_SERVER_URL}/download/builds/pingcap/devops/cachecode/${pipeline.repo}/${commitID}/${pipeline.repo}.tar.gz"
                originTask.pipelineName = pipeline.pipelineName
                originTask.triggerEvent = triggerEvent
                originTask.branch = branch 
                originTask.commitID = commitID
                originTask.pullRequest = pullRequest
                originTask.cacheCodeURL = cacheCodeUrl
                originTask.repo = pipeline.repo
                originTask.owner = pipeline.owner
                def taskJsonString = new JsonBuilder(originTask).toPrettyString()

                def params = [
                string(name: "INPUT_JSON", value: taskJsonString),
                ]
                def result = build(job: originTask.checkerName, parameters: params, wait: true, propagate: false)
                if (result.getResult() != "SUCCESS") {
                    throw new Exception("${originTask.taskName} failed")
                }
            }
        }
        pipeline.status = "running" 
        updatePipelineRun(pipeline)
        parallel jobs
    } catch (Exception e) {
        pipeline.status = "failed"
        updatePipelineRun(pipeline)
        throw e
    }
    pipeline.status = "passed" 
    updatePipelineRun(pipeline)
}



def loadTaskConfig(String config) {
    ObjectMapper objectMapper = new ObjectMapper()
    TaskSpec taskSpec = objectMapper.readValue(config, TaskSpec.class)
    if (taskSpec.timeout == null || taskSpec.timeout <= 1) {
        taskSpec.timeout = 60
    }
    if (taskSpec.retry == null || taskSpec.retry > 10) {
        taskSpec.retry = 0
    }
    taskSpec.resources = defaultResourceValue(taskSpec.resources)
    return taskSpec
}

def defaultResourceValue(Resources resource) {
    resp = new Resources()
    limit = new Resource()
    request = new Resource()
    resp.limits = limit
    resp.requests = request
    if (resource == null) {
        resp.requests.cpu = "1000m"
        resp.requests.memory = "2Gi"
        resp.limits.cpu = "1000m"
        resp.limits.memory = "2Gi"
        return resp
    }
    if (resource.requests == null || resource.requests.cpu.length() < 1 || resource.requests.memory.length() < 1) {
        resp.requests.cpu = "1000m"
        resp.requests.memory = "2Gi"
        resp.limits.cpu = "1000m"
        resp.limits.memory = "2Gi"
        return resp
    }
    if (resource.limits == null || resource.limits.cpu.length() < 1 || resource.limits.memory.length() < 1) {
        resp.limits.cpu = resource.requests.cpu
        resp.limits.memory = resource.requests.memory
        return resp
    }
    resp = resource
    return resp
}


def updateTaskStatus(TaskSpec config) {
    // update taskrun to tipipeline by task_id
    response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'PUT', requestBody: new JsonBuilder(config).toPrettyString(), url: "http://172.16.5.15:30792/taskrun", validResponseCodes: '200'
}

def runWithPod(TaskSpec config, Closure body) {
    def label = config.pipelineName + "-" + config.taskName + "-" + UUID.randomUUID().toString()
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'node', alwaysPullImage: false,
                            image: config.image, ttyEnabled: true,
                            resourceRequestCpu: config.resources.requests.cpu, resourceRequestMemory: config.resources.requests.memory,
                            resourceLimitCpu: config.resources.limits.cpu, resourceLimitMemory: config.resources.limits.memory,
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                    nfsVolume(mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false),
            ],
    ) {
        try {
            credentialList =[]
            for (credential in config.credentials) {
                credentialList.push(string(credentialsId: credential.jenkinsID, variable: credential.key))
            }
            varList = []
            for (var in config.vars) {
                varList.push("${var.key}=${var.value}")
            }
            
            config.jenkinsRunURL = RUN_DISPLAY_URL
            config.status = "running"
            updateTaskStatus(config)
            timeout(time: config.timeout, unit: 'MINUTES') {
                retry(config.retry){
                    node(label) {
                        container("node") {
                            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
                            withCredentials(credentialList) {
                                withEnv(varList) {
                                    body(config) 
                                }
                            }  
                        }
                    }
                }
            }
        } catch (Exception e) {
            config.status = "failed"
            config.result = currentBuild.description
            updateTaskStatus(config)
            throw e
        } 
        config.status = "passed"
        config.result = currentBuild.description
        updateTaskStatus(config)
}  
}


return this




// String json = '{"retry" : 1,"timeout" : 10,"vars":[{"key":"123qwe"}],"params":{"a":"b"}}'

// taskSpec = loadConfig(json)
// println taskSpec.params["a"]