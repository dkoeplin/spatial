###.PHONY: spatial
.PHONY: apps spatial compile publish-local clean

BRANCH?=fpga

all: spatial

spatial:
	$(info $$BRANCH is [${BRANCH}])
	sbt spatial/compile

apps:
	sbt apps/compile

compile:
	sbt compile

publish-local:
	cd scala-virtualized; \
	sbt publish-local; \
	cd ../argon; \
	sbt publish-local; \
	cd ../spatial; \
	sbt publish-local

assembly:
	sbt spatial/assembly

switch:
	sh -c 'git pull'
	sh -c 'git checkout origin/${BRANCH}'
	sh -c 'git pull'
	sh -c 'git submodule foreach --recursive git pull'
	sh -c 'git submodule foreach --recursive checkout origin/${BRANCH}'

resources:
	cd spatial/core/resources
	find . -not -path '*/\.*' -type f > files_list

clean:
	sbt clean
