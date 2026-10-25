// Declarative pipeline. NOT executed: no Jenkins instance was available. Groovy was not linted.
// Credentials are referenced by ID only; create them in Jenkins (Manage Credentials). No secret values exist in this file.
//   nvd-api-key      : Secret text (optional, speeds up OWASP dependency-check)
//   cf-test-creds    : Username with password (Cloud Foundry test space)
// Required tool installations (Global Tool Configuration): jdk21, nodejs22. Docker + shellcheck on the agent for image/validation stages.
pipeline {
  agent { label 'linux && docker' }

  options {
    timestamps()
    disableConcurrentBuilds(abortPrevious: true)
    timeout(time: 60, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '30'))
  }

  parameters {
    booleanParam(name: 'DEPLOY_TEST', defaultValue: false, description: 'Deploy to Cloud Foundry test space (manual gate)')
  }

  tools {
    jdk 'jdk21'
    nodejs 'nodejs22'
  }

  environment {
    VERSION_BASE = '0.1'
    BACKEND_DIR  = 'backend'
    FRONTEND_DIR = 'frontend/messaging-console'
  }

  stages {
    stage('Version') {
      steps {
        script {
          env.APP_VERSION = "${env.VERSION_BASE}.${env.BUILD_NUMBER}"
          env.GIT_SHA7    = sh(returnStdout: true, script: 'git rev-parse --short=7 HEAD').trim()
          env.IMAGE_TAG   = "${env.APP_VERSION}-${env.GIT_SHA7}"
          currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.IMAGE_TAG}"
        }
      }
    }

    stage('Build and test') {
      parallel {
        stage('Backend verify') {
          steps { sh 'mvn -B -f ${BACKEND_DIR}/pom.xml verify' }
          post {
            always { junit allowEmptyResults: true, testResults: 'backend/target/surefire-reports/*.xml' }
            success { archiveArtifacts artifacts: 'backend/target/*.jar', fingerprint: true }
          }
        }
        stage('Frontend lint, test, build') {
          steps {
            dir(env.FRONTEND_DIR) {
              sh 'npm ci --no-audit --no-fund'
              sh 'npm run lint --if-present'
              sh 'npm test -- --watch=false --browsers=ChromeHeadless'
              sh 'npx ng build --configuration production'
            }
          }
        }
        stage('Static analysis') {
          steps { sh 'mvn -B -f ${BACKEND_DIR}/pom.xml -Pstatic-analysis -DskipTests verify' }
        }
      }
    }

    stage('Security and integration') {
      parallel {
        stage('OWASP dependency-check') {
          steps {
            withCredentials([string(credentialsId: 'nvd-api-key', variable: 'NVD_API_KEY')]) {
              sh 'mvn -B -f ${BACKEND_DIR}/pom.xml -Psecurity-scan -DskipTests verify -DnvdApiKey="$NVD_API_KEY"'
            }
          }
        }
        stage('npm audit') {
          steps { dir(env.FRONTEND_DIR) { sh 'npm audit --omit=dev --audit-level=high' } }
        }
        stage('Integration tests (Testcontainers)') {
          steps { sh 'mvn -B -f ${BACKEND_DIR}/pom.xml verify -Pcontainers' }
        }
        stage('Config validation') {
          steps {
            sh 'docker compose --env-file .env.example -f infrastructure/compose/docker-compose.yml config -q'
            sh 'node cloud-foundry/validate-manifest.mjs'
            sh 'bash scripts/validate-infra.sh'
          }
        }
      }
    }

    stage('Docker build and scan') {
      parallel {
        stage('Backend image') {
          steps {
            sh 'docker build -f infrastructure/docker/Dockerfile.backend --build-arg VERSION=${APP_VERSION} --build-arg GIT_SHA=${GIT_SHA7} -t messaging-backend:${IMAGE_TAG} .'
            sh 'trivy image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 messaging-backend:${IMAGE_TAG}'
            sh 'trivy image --format cyclonedx --output sbom-backend.cdx.json messaging-backend:${IMAGE_TAG}'
          }
        }
        stage('Frontend image') {
          steps {
            sh 'docker build -f infrastructure/docker/Dockerfile.frontend -t messaging-frontend:${IMAGE_TAG} .'
            sh 'trivy image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 messaging-frontend:${IMAGE_TAG}'
            sh 'trivy image --format cyclonedx --output sbom-frontend.cdx.json messaging-frontend:${IMAGE_TAG}'
          }
        }
      }
      post { always { archiveArtifacts artifacts: 'sbom-*.cdx.json', allowEmptyArchive: true } }
    }

    stage('Deploy to test') {
      when { allOf { branch 'main'; expression { return params.DEPLOY_TEST } } }
      steps {
        input message: 'Deploy to Cloud Foundry test space?', ok: 'Deploy'
        withCredentials([usernamePassword(credentialsId: 'cf-test-creds', usernameVariable: 'CF_USERNAME', passwordVariable: 'CF_PASSWORD')]) {
          sh '''
            cf api "$CF_API"
            cf auth
            cf target -o "$CF_ORG" -s "$CF_SPACE"
            bash cloud-foundry/scripts/deploy.sh test
          '''
        }
      }
    }

    stage('Post-deploy smoke') {
      when { allOf { branch 'main'; expression { return params.DEPLOY_TEST } } }
      steps { sh 'bash cloud-foundry/scripts/smoke.sh test' }
    }
  }

  post {
    failure { echo 'Build failed: see stage logs. Notifications are intentionally not configured in this template.' }
  }
}
