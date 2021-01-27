pipeline {
    agent {
      docker {
        image 'adoptopenjdk:11-jdk-hotspot'
        label 'jdk-11'
      }
    }
    stages {
        stage('Prepare') {
            steps {
                sh 'apt-get install -y git wget unzip'
                dir("/root") {
                    sh 'wget https://downloads.apache.org/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz'
                    sh 'tar -xvf apache-maven-3.6.3-bin.tar.gz'
                    sh 'ln -s /root/apache-maven-3.6.3/bin/mvn /usr/bin/mvn'
                }
            }
        }
        stage('Git checkout') {
            steps {
                git 'https://github.com/linagora/openpaas-james'
            }
        }
        stage('Test') {
            steps {
                sh 'git submodule init'
                sh 'git submodule update'
                sh 'mvn install -Dmaven.javadoc.skip=true -DskipTests -T1C'
                dir("openpaas-james") {
                    sh 'mvn -B test'
                }
            }
        }
    }
}
