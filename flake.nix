{
    description = "Custom swaybar stuff";

    inputs = {
        nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
        flake-utils.url = "github:numtide/flake-utils";
	#clj2nix.url = "github:jlesquembre/clj2nix";
    };

    outputs = { self, nixpkgs, flake-utils}: 
        with flake-utils.lib; eachSystem allSystems (system:
            let
                pkgs = import nixpkgs {
                    inherit system;
                };
	#	clojureDeps = import ./deps.nix;
		clojureDeps = import ./deps.nix { inherit (pkgs) fetchMavenArtifact fetchgit lib; };
		classp = clojureDeps.makeClasspaths {};
            in rec
                { 
                    devShell = pkgs.mkShell {
                        buildInputs = with pkgs; [
			   graalvm-ce
			   clojure
                        ];
                    };
		    packages.default = pkgs.stdenv.mkDerivation {

                       name = "swaybar2";
                       src = ./.;
		       buildInputs = [ 
			       pkgs.clojure
			       pkgs.graalvm-ce
			       pkgs.git
			       pkgs.bash
			       pkgs.coreutils
			       pkgs.unzip
		       ];
# CLJ_CONFIG = "${toString ./deps.edn}";
# CLJ_CONFIG_DIR = "${toString ./.}";
		       #HOME = "${TMPDIR}";
		       #CLJ_CONFIG = ./deps.edn;
		       # buildPhase = ''
		       #  mkdir -p out
		       #  ./buildit.sh
		       #  '';
		       # buildPhase = ''
		       #  runHook preBuild
		       #  cd $src
		       #  ./buildit.sh
		       #  runHook postBuild
		       #  '';
		       # buildPhase = ''
		       #  runHook preBuild
		       #  cd $src
		       #  mkdir -p target 
		       #  clj -Spath -A:uberjar \
		       #  -J-Dclojure.bundled.libs=${classp} \
		       #  -T:build uberjar :jar target/swaybar2.jar
		       #  ./buildit.sh
		       #  runHook postBuild
		       #  '';
# buildPhase = ''
#   runHook preBuild
#   export HOME=$TMPDIR
#   cd $src
#
#   mkdir -p $TMPDIR/target
#
# clojure \
#   -J-Dclojure.bundled.libs=${classp} \
#   -Spath \
#   -Sdeps '{:aliases {:build {:ns-default build}}}' \
#   -T:build uberjar :jar $TMPDIR/target/swaybar2.jar
#
#   # Optionally, create an uberjar manually
#   # or just copy the whole compiled output into $out
#
#   runHook postBuild
# '';

preBuild = ''
  echo "FAKE-KEY" > .open-ai-key
'';


# buildPhase = ''
#   runHook preBuild
#   export HOME=$TMPDIR
#   mkdir -p $TMPDIR/src
#   cp -r $src/src/* $TMPDIR/src/
#   mkdir -p $TMPDIR/classes
#   mkdir -p $TMPDIR/target
#
#   echo "(compile 'swaybar2.core)" > $TMPDIR/compile.clj
#
#   echo "Resolved classpath:"
#   echo "${classp}:$TMPDIR/src"
#   ls -R $TMPDIR/src
#
#   ${pkgs.openjdk}/bin/java \
#     -cp ${classp}:$TMPDIR/src \
#     -Dclojure.compile.path=$TMPDIR/classes \
#     clojure.main $TMPDIR/compile.clj
#
#   native-image \
#     -cp $TMPDIR/classes:${classp}:$TMPDIR/src \
#     swaybar2.core \
#     $TMPDIR/target/swaybar2
#
#   runHook postBuild
# '';
buildPhase = ''
  runHook preBuild
  export HOME=$TMPDIR
  mkdir -p $TMPDIR/target
  mkdir -p $TMPDIR/classes

   cp -r $src/src $TMPDIR/
   echo "(compile 'swaybar2.core)" > $TMPDIR/compile.clj

  ${pkgs.openjdk}/bin/java \
    -cp ${classp}:$TMPDIR/src \
    -Dclojure.compile.path=$TMPDIR/classes \
    clojure.main $TMPDIR/compile.clj


mkdir -p $TMPDIR/uberjar-classes

# Copy compiled classes
cp -r $TMPDIR/classes/* $TMPDIR/uberjar-classes/
cp reflect-config.json $TMPDIR
# Unpack all jars in classpath into the same folder
for jar in $(echo ${classp} | tr ':' '\n'); do
  if [[ -f "$jar" ]]; then
    unzip -q -o "$jar" -d $TMPDIR/uberjar-classes
  fi
done
# Make the uberjar manually
cd $TMPDIR/uberjar-classes
echo "Main-Class: swaybar2.core" > $TMPDIR/manifest.txt
jar cfm $TMPDIR/target/swaybar2.jar  $TMPDIR/manifest.txt .

# Now compile native image from the jar
native-image \
  --initialize-at-build-time \
  -jar $TMPDIR/target/swaybar2.jar \
  -H:Name=swaybar2 \
  -H:+ReportExceptionStackTraces \
  -H:ReflectionConfigurationFiles=$TMPDIR/reflect-config.json \
  --no-fallback \
  $TMPDIR/target/swaybar2


  runHook postBuild
'';
		       # buildPhase = ''
		       #  runHook preBuild
		       #  export HOME=$TMPDIR
		       #  cd $src
		       #  mkdir -p $TMPDIR/target
		       #  clojure \
		       #  -J-Dclojure.bundled.libs=${classp} \
		       #  -Spath \
		       #  -T:build uberjar :jar $TMPDIR/target/swaybar2.jar
		       #  runHook postBuild
		       #  '';
		       installPhase = ''
			       mkdir -p $out
			       cp -r $TMPDIR/target/* $out/
						      '';


		       # installPhase = ''
		       #  mkdir -p $out
		       #  cp -r target/* $out/
		       #  '';
		    };

         });
}
