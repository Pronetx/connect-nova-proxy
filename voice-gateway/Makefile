
build:
	mvn package
	cp target/s2s-voip-gateway*.jar docker/
	docker build --platform linux/amd64 -t s2s-voip-gateway:latest docker

run:
	docker run --rm -ti --network host --env-file environment s2s-voip-gateway:latest

run-bg:
	docker run -d --name s2s-voip-gateway --network host --env-file environment s2s-voip-gateway:latest
