def call() {
    pipeline {
        agent any
        environment {
        EC2_CREDENTIALS_ID = 'ec2-ssh-credential-utils'
        PUBLIC_IP = ''

        }
        
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
                        echo "🌿 Rama actual: ${env.BRANCH_NAME}"


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
            stage('Conexión') {
                steps {
                    script {
                        withCredentials([string(credentialsId: 'calidad-v1-diggi-utils', variable: 'INSTANCE_ID')]) {
                            def publicIp = sh(
                                script: '''
                                aws ec2 describe-instances --region $AWS_REGION \
                                --instance-ids $INSTANCE_ID \
                                --query "Reservations[].Instances[].PublicIpAddress" \
                                --output text 
                                ''',
                                returnStdout: true
                            ).trim()
                            echo "Salida del comando AWS: ${publicIp}"

                            withEnv(["PUBLIC_IP=${publicIp}"]) {
                                sh 'echo "La IP pública obtenida es: $PUBLIC_IP"'
                            }

                            env.PUBLIC_IP = publicIp
                        }
                    }
                }
            }

            stage('Despliegue') {
                steps {
                    script {
                        withEnv(["PUBLIC_IP=${env.PUBLIC_IP}"]) {
                            sshagent([env.EC2_CREDENTIALS_ID]) {
                                sh """
                                ssh forge@$PUBLIC_IP \
                                "set -e; \
                                echo "Obteniendo la IP privada..."; \
                                curl -s http://169.254.169.254/latest/meta-data/local-ipv4; \
                                echo "Listando archivos en /home/forge..."; \
                                cd /home/forge && ls -la"
                                """
                            }
                        }
                        
                    }
                }
                
            }
            /*stage('Despliegue') {
                steps {
                    script {
                        withCredentials([string(credentialsId: 'calidad-v1-diggi-utils', variable: 'INSTANCE_ID')]) {
                           sshagent([env.EC2_CREDENTIALS_ID]) {
                                sh """
                                ssh forge@${publicIp} \
                                "set -e; \
                                cd /home/forge/calidad-v1-diggi-utils \
                                git pull origin ${env.BRANCH_NAME}\
                                cd /home/forge/calidad-v1-diggi-utils"
                                """
                            }
                        }
                    }
                }
                
            }*/
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