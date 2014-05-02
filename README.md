Sircuit
=======

Sircuit is a Simple ChatOps Platform for your team.

## (Planned) Features
- Easy deployment to in-house environment
- Both RESTful APIs and IRC service are available
- Notification service integration
- Logging your messages into your favorite storage and searching them
- Living your daily life with useful chat bots
- Pluggable authentication

## Deployment
You can utilize Sircuit as simple and sufficient IRC server at this time :)

```
$ git clone https://github.com/okapies/sircuit.git
$ sbt assembly
$ java -jar target/scala-2.10/sircuit-server-assembly-0.1.0.jar
```

If you want to override [default settings](src/main/resources/application.conf), create
[*.conf file*](http://doc.akka.io/docs/akka/snapshot/general/configuration.html#including-files)
and specify system property with `-Dconfig.resource=???.conf` to load it. Example is the following:

```
include "application"

sircuit {
  api.irc {
    bind {
      address = "irc.yourcompany.com"
      port = 6667
    }
  }
}
```

## Future work
- RESTful and websocket interface
- Webhook support
- SSL support
- Authentication and authorization
- Message logging and retrieval
- Global/channel-specific setting
- Pluggable chatbot
- Notification support
- Servlet container support
