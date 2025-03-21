clj -T:build uber
native-image \
	--report-unsupported-elements-at-runtime \
	--initialize-at-build-time \
        --initialize-at-run-time=java.util.Random,java.security.SecureRandom,org.apache.http.impl.auth.NTLMEngineImpl \
	--no-fallback \
	--enable-https \
	--enable-url-protocols=http,https \
	--enable-all-security-services \
	--enable-native-access=ALL-UNNAMED \
	--allow-incomplete-classpath \
	-H:IncludeResources="commons-logging.properties" \
	-H:ReflectionConfigurationFiles=reflect-config.json \
	-jar target/swaybar2.jar target/swaybar2
