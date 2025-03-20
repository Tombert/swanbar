clj -T:build uber
native-image \
	--report-unsupported-elements-at-runtime \
	--initialize-at-build-time \
	--no-fallback \
	--enable-https \
	--enable-url-protocols=http,https \
	--enable-all-security-services \
	--enable-native-access=ALL-UNNAMED \
	--allow-incomplete-classpath \
	-H:ReflectionConfigurationFiles=reflect-config.json \
	-jar target/swaybar2.jar target/swaybar2
