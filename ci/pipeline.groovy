podTemplate(label: 'maven',

    containers: [
      containerTemplate(name: 'maven', image: 'maven:3.5.4-jdk-8-alpine', ttyEnabled: true, command: 'cat'),
      containerTemplate(name: 'builder', image: 'dockerregistry.arcticlake.com/builder', ttyEnabled: true, command: 'cat')
    ],
    volumes: [
        // hostPathVolume(mountPath: '/root/.m2/repository', hostPath: '/tmp/jenkins/.m2/repository')
        hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
    ]
) {

  node('maven') {
    stage('Checkout and build a Maven project') {
      git 'https://github.com/gitdr/demo.git'
      container('maven') {
        sh 'cd hello-world && mvn clean compile assembly:single'
        // withSonarQubeEnv('sonar') {
        //   sh 'cd hello-world && mvn org.sonarsource.scanner.maven:sonar-maven-plugin:3.6.0.1398:sonar'
        // }
      }
    }
    try {
      stage('Build and push container') {
        container('builder') {
          sh '''
            env
            cd ci
            kubectl apply -f registry
            kubectl apply -f registry-proxy
            docker build -t localhost:5000/builder/hello-world -f hello-world/Dockerfile ../hello-world
            curl -s --connect-timeout 5 \
              --max-time 10 \
              --retry 5 \
              --retry-delay 0 \
              --retry-max-time 60 \
              curl $(kubectl get pod $NODE_NAME -o go-template='{{.status.hostIP}}'):5000/v2/_catalog > /dev/null
            docker push localhost:5000/builder/hello-world
          '''
        }
      }

      stage('Deploy assets') {
        container('builder') {
          sh '''
            cd ci
            kubectl create -f hello-world/hello-world-deploy.yaml
            kubectl expose deploy hello-world --port=8080
            # wait for pod to come up
            until curl -s --connect-timeout 1 hello-world.default:8080 &>/dev/null; do sleep 1; done
          '''
        }
      }

      stage('Integration testing') {
        container('builder') {
          sh '''
            curl -v hello-world.default:8080
          '''
        }
      }
    } catch (caughtError) {
      sh '''
        tail -f /dev/null
      '''
    } finally {
      stage('Remove assets') {
        container('builder') {
          sh '''
            cd ci
            kubectl delete -f hello-world/hello-world-deploy.yaml || true
            kubectl delete svc hello-world || true
            kubectl delete -f registry || true
            kubectl delete -f registry-proxy || true
          '''
        }
      }
    }
  }
}
