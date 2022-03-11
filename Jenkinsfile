pipeline {
    agent any

    options {
        // Configure an overall timeout for the build.
        timeout(time: 2, unit: 'HOURS')
        disableConcurrentBuilds()
    }
    

    post {
        always {
            build propagate: false, job: 'Gatling Imap build'
        }
    }
}