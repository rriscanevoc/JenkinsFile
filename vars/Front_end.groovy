def call(Map config = [:]) {

    def EC2_CREDENTIALS_ID  = config.ec2_credentials    //Obtener credenciales de los parametros
    def Id_instance = config.id_AWS                     
    def Ruta_Servidor = config.path             //Corregir
    def folder = config.folder

    pipeline {
        agent any
        environment {
            RAMA = 'calidad'
        }
        stages {
            stage('Checkout') {
                steps {
                    checkout scm                //Clonado repositorio
                }
            }
            stage('Check Secrets') {
                steps {
                    script {
                        try {
                            sh 'git-secrets --scan'     //Escaneo de exposición a credenciales
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
                        echo "Rama actual: ${env.BRANCH_NAME}"

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
            stage('Compilado'){
                steps {
                    script {
                        try{
                            sh './scripts/build.sh'
                            echo "Comprimiendo archivos..."
                            sh "tar -czf ${folder}.tar.gz ${folder}/"
                            echo "Compilado y comprensión exitosa"

                        }
                        catch (Exception e) {
                            echo "Se encontraron problemas con el compilado."
                            error("Pipeline detenido por error en compilado.")
                        }
                        
                    }
                }
            }
            stage('Conexión') {
                steps {
                    script {
                        withCredentials([string(credentialsId: Id_instance, variable: 'INSTANCE_ID')]) {
                            def publicIp = ""

                            try{
                                publicIp = sh(
                                    script: '''
                                    aws ec2 describe-instances --region $AWS_REGION \
                                    --instance-ids $INSTANCE_ID \
                                    --query "Reservations[].Instances[].PublicIpAddress" \
                                    --output text 
                                    ''',
                                    returnStdout: true
                                ).trim()
                                echo "IP ${publicIp}"
                            }catch (Exception e) {
                            echo "Se encontraron problemas en ubicar la Ip del servidor."
                            error("Pipeline detenido por error en conexión.")
                            }
                            
                            try{
                                sshagent([EC2_CREDENTIALS_ID]) {
                                sh """
                                    echo "📡 Transfiriendo ${folder}.tar.gz a la instancia ${publicIp}..."
                                    scp -o StrictHostKeyChecking=no ${folder}.tar.gz forge@${publicIp}:/home/forge/${Ruta_Servidor}

                                    ssh forge@${publicIp} "cd /home/forge/${Ruta_Servidor} && \
                                        tar -xzvf ${folder}.tar.gz &&\
                                        rm -f ${folder}.tar.gz 
                                        "
                                    echo '✔ Archivo recibido exitosamente'
                                """
                                //tar -xzvf build.tar.gz"
                                }
                            }
                            catch (Exception e) {
                            echo "Se encontraron problemas en la transferencia."
                            error("Pipeline detenido por error en transferencia.")
                            }
                                      
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