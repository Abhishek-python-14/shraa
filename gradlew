#!/bin/sh
# Minimal Gradle wrapper launcher.
APP_HOME=$(cd "$(dirname "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$JAR" ]; then
  exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
else
  echo "gradle-wrapper.jar not found, using system gradle" >&2
  exec gradle "$@"
fi
