#clj -T:build uber
clj -T:build uberjar :jar target/swaybar2.jar
native-image \
	--report-unsupported-elements-at-runtime \
        --initialize-at-build-time \
	--no-fallback \
	--enable-https \
	--enable-url-protocols=http,https \
	--enable-all-security-services \
	--enable-native-access=ALL-UNNAMED \
	--allow-incomplete-classpath \
	--no-fallback \
	-H:IncludeResources="commons-logging.properties" \
	-H:ReflectionConfigurationFiles=reflect-config.json \
	-jar target/swaybar2.jar target/swaybar2
