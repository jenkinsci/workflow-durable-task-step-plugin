Pipeline: Nodes and Processes
===

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/workflow-durable-task-step)](https://plugins.jenkins.io/workflow-durable-task-step)
[![Changelog](https://img.shields.io/github/v/tag/jenkinsci/workflow-durable-task-step-plugin?label=changelog)](https://github.com/jenkinsci/workflow-durable-task-step-plugin/blob/master/CHANGELOG.md)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/workflow-durable-task-step?color=blue)](https://plugins.jenkins.io/workflow-durable-task-step)

A component of [Pipeline Plugin](https://wiki.jenkins.io/display/JENKINS/Pipeline+Plugin).

Pipeline steps locking agents and workspaces, and running external processes that may survive a Jenkins restart or agent reconnection.

## Documentation

The nodes and processes pluginalso called workflow durable task steps locks jenkins agents with the processes running on them, thereby adding durability to workflows. Pipelines can resume after an unforseeable restart.

### Pipelines

The plugin provides a `ws` step to allocate a workspace, however, this is created automatically using the `node` step. The [Pipeline Syntax Snippet Generator](https://www.jenkins.io/doc/book/pipeline/getting-started/#snippet-generator) guides the user on usage, practical examples and more information.

### Node

A node is the machine on which Jenkins runs, it is part of the Jenkins environment and is capable of executing a Pipeline. 
The `agent` syntax is equivalent to the `node` syntax except in different spheres.  agent is a [declarative syntax](https://www.jenkins.io/doc/book/pipeline/#declarative-pipeline-fundamentals) that is used to specify the executor and workspace on which the Pipeline will be executed while node is [scripted syntax](https://www.jenkins.io/doc/book/pipeline/#scripted-pipeline-fundamentals) that is used to execute Pipelines (and any stages contained within it), on any available agent/node

### Processes

A process in Jenkins can be defined as the continuous integration and continuous delivery of builds, tests, analysis, and deployment of projects and any other scenario that can be automated. The steps in these processes are documented in the jenkinsfile which can be created manually or added automatically using Blue Ocean.

Examples of nodes and processes in respect to declarative and scripted pipeline
1. A scripted pipeline example can be found [here](https://www.jenkins.io/doc/book/pipeline/#scripted-pipeline-fundamentals)

2. A declarative pipeline example can be found [here](https://www.jenkins.io/doc/book/pipeline/#declarative-pipeline-fundamentals), refer to the [Pipeline Syntax Snippet Generator](https://www.jenkins.io/doc/book/pipeline/getting-started/#snippet-generator) and  for more information

### Using multiple agents and setting labels

It is possible to run Jenkins pipeline on multiple agents. Pipelines that can run on low resource can be paired with equal powered agents and high resource agents with equal powered pipelines to avoid unnecessarily long build time.

A declarative pipeline with multiple agents:

'''
pipeline {
    agent none
    stages {
        stage('Build') {
            agent any
            steps {
                checkout scm
                sh 'make'
                stash includes: '**/target/*.jar', name: 'app' 
            }
        }
        stage('Test on Linux') {
            agent { 
                label 'linux'
            }
            steps {
                unstash 'app' 
                sh 'make check'
            }
            post {
                always {
                    junit '**/target/*.xml'
                }
            }
        }
        stage('Test on Windows') {
            agent {
                label 'windows'
            }
            steps {
                unstash 'app'
                bat 'make check' 
            }
            post {
                always {
                    junit '**/target/*.xml'
                }
            }
        }
    }
}
'''
A scripted pipeline with multiple agents

'''
stage('Test') {
    node('linux') { 
        checkout scm
        try {
            unstash 'app' 
            sh 'make check'
        }
        finally {
            junit '**/target/*.xml'
        }
    }
    node('windows') {
        checkout scm
        try {
            unstash 'app'
            bat 'make check' 
        }
        finally {
            junit '**/target/*.xml'
        }
    }
}
'''
Refer to this [article](https://docs.cloudbees.com/docs/admin-resources/latest/automating-with-jenkinsfile/using-multiple-agents) for a detailed explanation.

* [Changelog](https://github.com/jenkinsci/workflow-durable-task-step-plugin/blob/master/CHANGELOG.md)


## License

[MIT License](https://opensource.org/licenses/mit-license.php)
