def call() {
    pipeline {
        agent any
        environment {
        EC2_CREDENTIALS_ID = 'ec2-ssh-credential-utils'  // ID de credenciales en Jenkins
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
                        echo "🔍 Detectando cambios en el repositorio..."
                        echo "🌿 Rama actual: ${env.BRANCH_NAME}"
                        echo "📌 Rama origen (PR): ${env.CHANGE_BRANCH}"
                        echo "🎯 Rama destino (PR): ${env.CHANGE_TARGET}"

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
            stage('Conectar a EC2 y obtener IP privada') {
            steps {
                script {
                    sshagent([EC2_CREDENTIALS_ID]) {
                        sh """
                        ssh ubuntu@${EC2_PUBLIC_IP} << 'EOF'
                        echo "Obteniendo la IP privada..."
                            curl -s http://169.254.169.254/latest/meta-data/local-ipv4
                            echo "Listando archivos en /home/forge..."
                            cd /home/forge && ls -la
                        EOF
                        """
                        }
                    
                    }
                }
            }
        }
        
        post {
            always {
                cleanWs()
            }
        }
    }
}
