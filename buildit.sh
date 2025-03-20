clj -T:build uber
native-image --report-unsupported-elements-at-runtime --initialize-at-build-time -jar target/swaybar2.jar target/swaybar2
