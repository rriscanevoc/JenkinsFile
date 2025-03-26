def call(Map config = [:]) {

    def EC2_CREDENTIALS_ID  = config.Ec2_credentials    //Obtener credenciales de los parametros
    def Id_instance = config.Id_AWS                     
    def Ruta_Servidor = config.Ruta

    pipeline {
        agent any
        environment {
            //EC2_CREDENTIALS_ID = 'ec2-ssh-credential-utils'
            //Id_instance = 'calidad-v1-diggi-utils'
            FORGE_COMPOSER = 'php8.1 /usr/local/bin/composer'
            RAMA = 'calidad-viejo'
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
                                    ssh forge@${publicIp}\                              //Conexión con esl servidor a traves de usuario forge
                                    "set -e;\
                                    echo "Desplegando...";\
                                    cd /home/forge/${env.Ruta_Servidor} && git pull origin ${env.RAMA};\
                                    ${env.FORGE_COMPOSER} install --no-dev --no-interaction --prefer-dist --optimize-autoloader;\
                                    ( flock -w 10 9 || exit 1;\
                                        echo 'Restarting FPM...';\
                                        sudo -S service php8.1-fpm reload ) 9>/tmp/fpmlock;\

                                        if [ -f artisan ]; then php8.1 artisan migrate --force;fi;\
                                    echo 'Despliegue completado exitosamente.'"           
                                    """
                                }
                            
                            }catch (Exception e) {
                            echo "Se encontraron problemas en el despliegue."
                            error("Pipeline detenido por error en error en despliegue.")
                            }

                            try{
                                sshagent([EC2_CREDENTIALS_ID]) {
                                sh """
                                    echo "Comprimiendo archivos..."
                                    tar -czf build.tar.gz /var/lib/jenkins//comprimir/
                    
                                    echo "📡 Transfiriendo build.tar.gz a la instancia ${publicIp}..."
                                    scp -o StrictHostKeyChecking=no build.tar.gz forge@${publicIp}:/home/forge/

                                    echo "Verificando que el archivo fue recibido..."
                                ssh forge@${publicIp} "ls -lh /home/forge/build.tar.gz && echo '✔ Archivo recibido exitosamente'"
                                """
                                }
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