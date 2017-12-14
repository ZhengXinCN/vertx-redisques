import groovy.json.JsonSlurper

class Example {
    int delayBetweenRetries = 30
    int numberOfRetries = 10

    static void main(String[] args) {
        def stagingHelper = new Staging(System.getenv("CI_DEPLOY_USERNAME"), System.getenv("CI_DEPLOY_PASSWORD"));

        switch (args[0]) {
            case "close":
                println "trying to close nexus repository ..."
                doWithRetry(stagingHelper.close())
                println " > done"
                break;
            case "drop":
                println "trying to drop nexus repository ..."
                doWithRetry(stagingHelper.drop())
                println " > done"
                break;
            case "promote":
                println "trying to promote nexus repository ..."
                doWithRetry(stagingHelper.promote())
                println " > done"
                break;
        }
    }

    int doWithRetry(Closure operation) {
        int counter = 0
        int numberOfAttempts = Integer.valueOf(numberOfRetries) + 1
        while (true) {
            try {
                counter++
                println "Attempt $counter/$numberOfAttempts..."
                return operation()
            } catch (Exception e) {
                if (counter >= numberOfAttempts) {
                    println "Giving up."
                    throw e
                } else {
                    waitBeforeNextAttempt()
                }
            }
        }
    }

    void waitBeforeNextAttempt() {
        sleep(Integer.valueOf(delayBetweenRetries))
    }
}

class Staging {
    String ossUserName
    String ossPassword

    Staging(String userName, String password) {
        ossUserName = userName
        ossPassword = password
    }

    public void drop() throws Exception {
        def stagingProfileId = getStagingProfileId()
        def repositoryId = getRepositoryId(stagingProfileId, "released");
        def data = getData(stagingProfileId, repositoryId)

        // drop repository
        def response = ['bash', '-c', "curl -sL -w \"%{http_code}\" -H \"Content-Type: application/json\" -X POST -d '" + data + "' https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profiles/" + stagingProfileId + "/drop -o /dev/null"].execute().text
        println response

        if (Integer.valueOf(response) > 299) {
            throw new IllegalArgumentException("HTTP request failed, getting status code: ${response}")
        }
    }


    public void close() {
        def stagingProfileId = getStagingProfileId()
        def repositoryId = getRepositoryId(stagingProfileId, "open");
        def data = getData(stagingProfileId, repositoryId)

        // close repository
        def response = ['bash', '-c', "curl -sL -w \"%{http_code}\" -H \"Content-Type: application/json\" -X POST -d '" + data + "' https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profiles/" + stagingProfileId + "/finish -o /dev/null"].execute().text
        println response

        if (Integer.valueOf(response) > 299) {
            throw new IllegalArgumentException("HTTP request failed, getting status code: ${response}")
        }
    }

    public void promote() {
        def stagingProfileId = getStagingProfileId()
        def repositoryId = getRepositoryId(stagingProfileId, "closed");
        def data = getData(stagingProfileId, repositoryId)

        // promote repository
        def response = ['bash', '-c', "curl -sL -w \"%{http_code}\" -H \"Content-Type: application/json\" -X POST -d '" + data + "' https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profiles/" + stagingProfileId + "/promote -o /dev/null"].execute().text
        println response

        if (Integer.valueOf(response) > 299) {
            throw new IllegalArgumentException("HTTP request failed, getting status code: ${response}")
        }
    }

    public String getStagingProfileId() {
        def response = ['bash', '-c', "curl -s -H \"Accept: application/json\" -X GET https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profiles"].execute().text

        def json = new JsonSlurper().parseText(response)
        def profileList = json.data
        int found = 0;
        String stagingProfileId = "";
        for (int index = 0; index < profileList.size(); index++) {
            if (profileList[index].name.equals("org.swisspush")) {
                found++;
                stagingProfileId = profileList[index].id
            }
        }

        if (found == 0) {
            throw new IllegalArgumentException("No stagingProfileId found!")
        } else if (found > 1) {
            throw new IllegalArgumentException("Multiple stagingProfileId's found!")
        }

        println "Found stagingProfileId: " + stagingProfileId

        return stagingProfileId
    }

    public getRepositoryId(String stagingProfileId, String state) {
        def response = ['bash', '-c', "curl -s -H \"Accept: application/json\" -X GET https://" + ossUserName + ":" + ossPassword + "@oss.sonatype.org/service/local/staging/profile_repositories/" + stagingProfileId].execute().text
        def json = new JsonSlurper().parseText(response)

        if (json.data.size() == 0) {
            throw new IllegalArgumentException("No staging repository found!")
        } else if (json.data.size() > 1) {
            throw new IllegalArgumentException("Multiple staging repositories found!")
        }

        def repository = json.data[0]

        // check state - close
        if (state.equals("open") && !repository.type.equals(state)) {
            throw new IllegalArgumentException("No open repository found!")
        }

        if (state.equals("closed") && !repository.type.equals(state)) {
            throw new IllegalArgumentException("No closed repository found!")
        }

        println "Found repositoryId: " + repository.repositoryId

        return repository.repositoryId
    }

    public String getData(String stagingProfileId, String repositoryId) {
        return "{\"data\" : {\"stagedRepositoryId\" : " + repositoryId + ",\"description\" : \"Automatically released/promoted with Post JenkinsIT!\",  \"targetRepositoryId\" : " + stagingProfileId + " }}"
    }

}