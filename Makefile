idea:
	mill -i mill.idea.GenIdea/idea

init:
	git submodule update --init
	cd dependencies/nanhu && git submodule update --init coupledL2 huancun
	cd dependencies/nanhu/coupledL2 && git submodule update --init AXItoTL

comp:
	mill -i nansha.compile
	mill -i nansha.test.compile