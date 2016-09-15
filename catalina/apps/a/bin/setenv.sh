CATALINA_OPTS="$JAVA_OPTS -Djava.library.path=$CATALINA_HOME/lib  -Xmx1024m -XX:MetaspaceSize=64m -XX:MaxMetaspaceSize=256m  -server -XX:+PrintGCDetails -XX:+PrintGCApplicationStoppedTime -Xloggc:/home/log/tomcatagc.log"

#args="-J-Xms256m -J-Xmx768m"

CATALINA_PID=$CATALINA_BASE/logs/catalina.pid

#CATALINA_OPTS="$JAVA_OPTS -Djava.library.path=$CATALINA_HOME/lib -Xmx1g  -server  -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true "
#CATALINA_OPTS="$JAVA_OPTS -Djava.library.path=$CATALINA_HOME/lib -Xmx1g  -XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:SurvivorRatio=2 -XX:NewRatio=8  -server  -Dorg.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES=true "
