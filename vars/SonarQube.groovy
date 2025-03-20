def call() {
    pipeline {
        agent any
        
        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Check Secrets') {
                steps {
                    script {
                        try {
                            sh 'git-secrets --scan'
                        } catch (Exception e) {
                            echo "Se encontraron secretos en el código. Revisa antes de continuar."
                            error("Pipeline detenido por exposición de credenciales.")
                        }
                    }
                }
            }
            stage('Detectar archivos modificados') {
                steps {
                    script {
                        echo "🔍 Detectando cambios en el repositorio..."
                        echo "🌿 PR actual: ${env.BRANCH_NAME}"
 
                        def changedFiles = sh(
                            script: "git diff --name-status HEAD~1 HEAD",
                            returnStdout: true
                        ).trim()

                        if (changedFiles) {
                            echo "Archivos modificados en el último push:"
                            echo "${changedFiles}"
                            
                            writeFile file: 'changed-files.txt', text: changedFiles
                            archiveArtifacts artifacts: 'changed-files.txt', fingerprint: true
                        } else {
                            echo "No se detectaron cambios en archivos."
                        }
                    }
                }
            }
            stage('SonarQube Analysis') {
                steps {
                    script {
                    
                        def scannerHome = tool 'sonarscanner'
                        echo "-${scannerHome}-"
                        echo "-/var/lib/jenkins/tools/hudson.plugins.sonar.SonarRunnerInstallation/sonarscanner-"

                        try{
                        withSonarQubeEnv('sonarqube') {
                            sh """
                              ${scannerHome}/bin/sonar-scanner -Dsonar.working.directory=.scannerwork -X
                            """

                        /*    sh """
                            /var/lib/jenkins/tools/hudson.plugins.sonar.SonarRunnerInstallation/sonarscanner/bin/sonar-scanner \
                                -Dsonar.projectKey=famiefi-api-utils \
                                -Dsonar.host.url=http://44.247.49.190:9002 \
                                -Dsonar.token=sqp_f425e7a673e249da66d856799b576a7dca6afccb \
                                -Dsonar.sources=app/ \
                                -Dsonar.working.directory=.scannerwork \
                                -X
                           """*/
                        
                        }
                        } catch (Exception e) {
                            echo "Se encontró error. Revisa antes de continuar."
                            error("Pipeline detenido por exposición de credenciales.")
                        }

                    }
                }
            }
            
        }
        post {
            success {
                echo " Despliegue completado exitosamente"
            }
            failure {
                echo " El despliegue falló"
            }   
            always {
                cleanWs()
            }
        }
    }
}