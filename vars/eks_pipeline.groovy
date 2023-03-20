def call(Map config = [:]) {
    pipeline {
        agent { label 'worker' }

        tools {
            nodejs "NodeJs"
        }

        options {
          disableConcurrentBuilds(abortPrevious: true)
        }
        
        stages {
            stage('Start') {
                steps {

                    script {
                        GIT_COMMIT_MESSAGE = sh (
                            script: 'git log --format="medium" -1 ${GIT_COMMIT}',
                            returnStdout: true
                        )
                    } 
                    slackSend (
                        color: '#2EB885', 
                        message: "STARTED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'\n```${GIT_COMMIT_MESSAGE}```"
                    )
                }
            }

            stage('Build') {
                steps {
                    sh 'npm ci'
                }
            }

            stage('Test') {
                steps {
                    sh 'npm run test'
                }
            }

            stage('Code Quality') {

                environment {
                    scannerHome = tool 'SonarQube'
                }

                steps {

                    script {
                        STAGE_NAME = "Code Quality Check"
                        PROJECT_NAME = sh (
                            script: 'jq -r .name package.json',
                            returnStdout: true
                        ).trim()
                    }

                    writeFile file: 'sonar-project.properties', text: "sonar.projectKey=${PROJECT_NAME}\nsonar.projectName=${PROJECT_NAME}\nsonar.projectVersion=${GIT_COMMIT}\nsonar.sources=./\nsonar.language=js\nsonar.sourceEncoding=UTF-8"

                    sh 'cat sonar-project.properties'

                    withSonarQubeEnv('SonarQube') {
                        sh "${scannerHome}/bin/sonar-scanner" 
                    }

                    timeout(time: 15, unit: 'MINUTES') {
                        waitForQualityGate abortPipeline: true
                    }

                }

            }

            stage('Develop Deployment') {
                when { branch "develop" }

                steps {
                    copyArtifacts(projectName: 'actyv-ci-cd-base/dev', filter: '**/*.sh')
                    script {
                        STAGE_NAME = "Develop Deployment"
                        APP_NAME = sh (
                            script: 'jq -r .family taskdef.json',
                            returnStdout: true
                        ).trim()
                    }
                    withVault(configuration: [timeout: 60, vaultCredentialId: 'ee923218-934f-42e1-802d-4a0396ba5d6f', vaultUrl: 'http://10.120.10.87:8200'], vaultSecrets: [[engineVersion: 2, path: "kv/${APP_NAME}-dev", secretValues: [[envVar: 'secret', vaultKey: 'secret']] ]]) {
                        sh('#!/bin/sh -e\n' + "echo ${env.secret} | sed \"s/{//g\" | sed \"s/}//g\" | sed \"s/,/\\n/g\" | sed \"s/:/=/\" > .env")
                    }
                    
                    script {
                        if (config.build) {
                            stage ('npm build') {
                                sh 'rm -f src/config/config.js src/config/qa.config.js uat.config.js prod.config.js'
                                sh '[ -f src/config/dev.config.js ] && mv src/config/dev.config.js src/config/config.js'
                                sh 'npm run build'
                            }
                        }
                        if(config.copyConfig) {
                            stage ('copy config') {
                                sh 'rm -f src/config/config.ts src/config/qa.config.ts uat.config.ts prod.config.ts'
                                sh '[ -f src/config/dev.config.ts ] && mv src/config/dev.config.ts src/config/config.ts'
                            }
                        }


                    }
                    sh 'bash deployment-scripts/applications/dev/eks_deploy.sh dev'
                    
                }
            }

            stage('Release Deployment') {

                when { branch "release/sprint/*" }

                stages('Release Deployment Flow') {

                    stage('Deployment Method Selection and QA Approval') {
                        agent none
                        options {
                            timeout(time: 7, unit: 'DAYS') 
                        }
                        steps {
                            script {
                                slackSend (
                                    color: '#2EB885',
                                    message: "Awaiting Deployment Method Selection and Approval for the Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
                                )
                                def INPUT_PARAMS = input message: 'Deployment Type Selection', ok:'Next',parameters: [choice(choices: ['Release', 'Hotfix'], name: 'Deployment Types')]
                                env.Environment=INPUT_PARAMS
                                println("Selected Deployment type : " + env.Environment)
                            }
                            copyArtifacts(projectName: 'actyv-ci-cd-base/dev', filter: '**/*.sh')
                            script {
                                if (config.build) {
                                    stage ('npm build') {
                                        sh 'rm -f src/config/config.js'
                                        sh '[ -f src/config/qa.config.js ] && mv src/config/qa.config.js node_modules'
                                        sh '[ -f src/config/uat.config.js ] && mv src/config/uat.config.js node_modules'
                                        sh '[ -f src/config/prod.config.js ] && mv src/config/prod.config.js node_modules'
                                    }
                                }
                                if(config.copyConfig) {
                                    stage ('copy config') {
                                        sh 'rm -f src/config/config.ts'
                                        sh '[ -f src/config/qa.config.ts ] && mv src/config/qa.config.ts node_modules'
                                        sh '[ -f src/config/uat.config.ts ] && mv src/config/uat.config.ts node_modules'
                                        sh '[ -f src/config/prod.config.ts ] && mv src/config/prod.config.ts node_modules'
                                    }
                                }
                            }
                        }
                    }
                    
                    stage("Started Deployment to QA") {
                        when { expression { env.Environment == "Release" } }
                        steps {
                            script {
                                STAGE_NAME = "Started Deployment to QA"
                                APP_NAME = sh (
                                    script: 'jq -r .family taskdef.json',
                                    returnStdout: true
                                ).trim()
                            }
                            withVault(configuration: [timeout: 60, vaultCredentialId: 'ee923218-934f-42e1-802d-4a0396ba5d6f', vaultUrl: 'http://10.120.10.87:8200'], vaultSecrets: [[engineVersion: 2, path: "kv/${APP_NAME}-qa", secretValues: [[envVar: 'secret', vaultKey: 'secret']] ]]) {
                                sh('#!/bin/sh -e\n' + "echo ${env.secret} | sed \"s/{//g\" | sed \"s/}//g\" | sed \"s/,/\\n/g\" | sed \"s/:/=/\" > .env")
                            }
                            script {
                                if (config.build) {
                                    stage ('npm build') {
                                        sh 'mv node_modules/qa.config.js src/config/config.js'
                                        sh 'npm run build'
                                    }
                                }
                                if(config.copyConfig) {
                                    stage ('copy config') {
                                        sh 'mv node_modules/qa.config.ts src/config/config.ts'
                                    }
                                }
                            }
                            sh 'bash deployment-scripts/applications/dev/ecs_deploy.sh qa'
                            sh 'rm -rf build src/config/config.js'
                            slackSend (
                                color: '#2EB885', 
                                message: "QA deployment completed for the Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
                            )
                        }
                    }

                    stage('Approval to UAT') {
                        when { expression { env.Environment == "Release" } }
                        agent none
                        options {
                            timeout(time: 7, unit: 'DAYS') 
                        }
                        steps {
                            slackSend (
                                color: '#2EB885', 
                                message: "Awaiting UAT approval for the job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
                            )
                            script {
                                def approver = input id: 'Deploy', message: 'Deploy to UAT?', submitter: 'anupam,anand,sutirtha,pradeep', submitterParameter: 'deploy_approver'
                                echo "This deployment was approved by ${approver}"
                            }
                        }
                    }

                    stage("Started Deployment to UAT") {
                        steps {
                            script {
                                STAGE_NAME = "Started Deployment to UAT"
                                APP_NAME = sh (
                                    script: 'jq -r .family taskdef.json',
                                    returnStdout: true
                                ).trim()
                            }
                            withVault(configuration: [timeout: 60, vaultCredentialId: 'ee923218-934f-42e1-802d-4a0396ba5d6f', vaultUrl: 'http://10.120.10.87:8200'], vaultSecrets: [[engineVersion: 2, path: "kv/${APP_NAME}-uat", secretValues: [[envVar: 'secret', vaultKey: 'secret']] ]]) {
                                sh('#!/bin/sh -e\n' + "echo ${env.secret} | sed \"s/{//g\" | sed \"s/}//g\" | sed \"s/,/\\n/g\" | sed \"s/:/=/\" > .env")

                            }
                            script {
                                if (config.build) {
                                    stage ('npm build') {
                                        sh 'mv node_modules/uat.config.js src/config/config.js'
                                        sh 'npm run build'
                                    }
                                }
                                if(config.copyConfig) {
                                    stage ('copy config') {
                                        sh 'mv node_modules/uat.config.ts src/config/config.ts'
                                    }
                                }
                            }
                            sh 'bash deployment-scripts/applications/dev/ecs_deploy.sh uat'
                            sh 'rm -rf build src/config/config.js'
                            slackSend (
                                color: '#2EB885', 
                                message: "UAT deployment completed for the Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
                            )
                        }
                    }

                    stage('Approval to PROD') {
                        agent none
                        options {
                            timeout(time: 7, unit: 'DAYS') 
                        }
                        steps {
                            slackSend (
                                color: '#2EB885', 
                                message: "Awaiting PROD approval for the Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
                            )
                            script {
                                def approver = input id: 'Deploy', message: 'Deploy to PROD?', submitter: 'anupam,anand,sutirtha,pradeep', submitterParameter: 'deploy_approver'
                                echo "This deployment was approved by ${approver}"
                            }
                        }
                    }

                    stage("Started Deployment to PROD") {
                        steps {
                            copyArtifacts(projectName: 'actyv-ci-cd-base/master', filter: '**/*.sh')
                            script {
                                STAGE_NAME = "Started Deployment to PROD"
                                APP_NAME = sh (
                                    script: 'jq -r .family taskdef.json',
                                    returnStdout: true
                                ).trim()
                            }
                            withVault(configuration: [timeout: 60, vaultCredentialId: 'ee923218-934f-42e1-802d-4a0396ba5d6f', vaultUrl: 'http://10.120.10.87:8200'], vaultSecrets: [[engineVersion: 2, path: "kv/${APP_NAME}-prod", secretValues: [[envVar: 'secret', vaultKey: 'secret']] ]]) {
                                sh('#!/bin/sh -e\n' + "echo ${env.secret} | sed \"s/{//g\" | sed \"s/}//g\" | sed \"s/,/\\n/g\" | sed \"s/:/=/\" > .env")
                            }
                            script {
                                if (config.build) {
                                    stage ('npm build') {
                                        sh 'mv node_modules/prod.config.js src/config/config.js'
                                        sh 'npm run build'
                                    }
                                }
                                if(config.copyConfig) {
                                    stage ('copy config') {
                                        sh 'mv node_modules/prod.config.ts src/config/config.ts'
                                    }
                                }
                            }
                            sh 'npm run deploy-production'
                            sh 'rm -rf build src/config/config.js'
                            slackSend (
                                color: '#2EB885', 
                                message: "PROD deployment completed for the Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
                            )
                        }
                    }
                }
            }
        }

        post {

            always {

                sh 'npm run generate-report'
                
                sh "echo ${JOB_NAME}"
                sh "REPOSITORY_NAME=`echo $JOB_NAME | cut -d / -f1`"
                sh "echo ${REPOSITORY_NAME}"
                sh "echo ${BRANCH_NAME}"
                sh "echo ${BUILD_NUMBER}"
                
                sh "aws s3 cp index.html s3://jenkins-report-archieve/${JOB_NAME}/branches/${BRANCH_NAME}/builds/${BUILD_NUMBER}/htmlreports/unit-test-report/"
                sh "echo ${JOB_NAME}"
                sh "echo ${BRANCH_NAME}"
                sh "echo ${BUILD_NUMBER}"
                
                publishHTML target: [
                    allowMissing: false,
                    alwaysLinkToLastBuild: false,
                    keepAll: true,
                    reportDir: './',
                    reportDir: 'tests-report',
                    reportFiles: 'index.html',
                    reportName: 'unit-test-report',
                    includes: 'index.html'
                ]

                sh "npm run sonar -- --allbugs true --no-security-hotspot true --sonarurl ${SONARQUBE_URL} --sonarusername ${SONAR_USERNAME} --sonarpassword ${SONAR_PASSWORD} --sonarcomponent ${PROJECT_NAME} --project ${PROJECT_NAME} --application ${PROJECT_NAME} --release ${GIT_COMMIT}"
                
                sh "aws s3 cp code_quality_report.html s3://jenkins-report-archieve/${REPOSITORY_NAME}/branches/${BRANCH_NAME}/builds/${BUILD_NUMBER}/htmlreports/code-quality-report/"
                
                publishHTML target: [
                    allowMissing: false,
                    alwaysLinkToLastBuild: false,
                    keepAll: true,
                    reportDir: './',
                    reportFiles: 'code_quality_report.html',
                    reportName: 'code-quality-report',
                    includes: 'code_quality_report.html'
                ]

            }

            success {

                slackSend(
                     color: '#2EB885', 
                     message: "Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'\n ``` UNIT TEST REPORT: https://jenkins.actyv.com/job/${JOB_NAME.split('/')[0]}/job/${JOB_BASE_NAME}/${BUILD_NUMBER}/unit-test-report/\n CODE QUALITY REPORT: https://jenkins.actyv.com/job/${JOB_NAME.split('/')[0]}/job/${JOB_BASE_NAME}/${BUILD_NUMBER}/code-quality-report/\n STATUS:SUCCESSFUL ``` "

                )
            }

            unstable {
                slackSend(
                color: '#DAA039',
                message: "Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'\n ``` UNIT TEST REPORT: https://jenkins.actyv.com/job/${JOB_NAME.split('/')[0]}/job/${JOB_BASE_NAME}/${BUILD_NUMBER}/unit-test-report/\n CODE QUALITY REPORT: https://jenkins.actyv.com/job/${JOB_NAME.split('/')[0]}/job/${JOB_BASE_NAME}/${BUILD_NUMBER}/code-quality-report/\n STATUS:UNSTABLE ``` "
                )
            }

            failure {
                slackSend(
                color: '#A30101', 
                message: "Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'\n ``` UNIT TEST REPORT: https://jenkins.actyv.com/job/${JOB_NAME.split('/')[0]}/job/${JOB_BASE_NAME}/${BUILD_NUMBER}/unit-test-report/\n CODE QUALITY REPORT: https://jenkins.actyv.com/job/${JOB_NAME.split('/')[0]}/job/${JOB_BASE_NAME}/${BUILD_NUMBER}/code-quality-report/\n STATUS:FAILED ``` "
                )
            }
            
            aborted {

                /* clean up our workspace */
                deleteDir()

                /* clean up tmp directory */
                dir("${workspace}@tmp") {
                    deleteDir()
                }

                /* clean up script directory */
                dir("${workspace}@script") {
                    deleteDir()
                }
                
                /* clean up libs directory */
                dir("${workspace}@libs") {
                    deleteDir()
                }

            }

            cleanup {

                /* clean up our workspace */
                deleteDir()

                /* clean up tmp directory */
                dir("${workspace}@tmp") {
                    deleteDir()
                }

                /* clean up script directory */
                dir("${workspace}@script") {
                    deleteDir()
                }
                
                /* clean up libs directory */
                dir("${workspace}@libs") {
                    deleteDir()
                }

            }
        }
    }
}
