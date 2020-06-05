AlgaBus {
	var <server;
	var <bus;
	var busArg; // cache for "/s_new" bus arg
	var <rate = nil, <numChannels = 0;

	*new { | server, numChannels = 1, rate = \audio |
		^super.new.init(server, numChannels, rate);
	}

	init { | argServer, argNumChannels = 1, argRate = \audio |
		server = argServer;
		this.newBus(argNumChannels, argRate);
	}

	newBus { | argNumChannels = 1, argRate = \audio |
		rate = argRate;
		numChannels = argNumChannels;
		bus = Bus.alloc(rate, server, numChannels); //Should I wait on this alloc?
		this.makeBusArg;
	}

	free {
		if(bus != nil, {
			bus.free(true);
		});
		rate = nil;
		numChannels = 0;
		busArg = nil;
	}

	//Define getter
	busArg { ^busArg ?? { this.makeBusArg } }

	//This allows multichannel bus to be used when patching them with .busArg !
	makeBusArg {
		var index, prefix;
		if(bus.isNil) { ^busArg = "" }; // still neutral
		prefix = if(rate == \audio) { "\a" } { "\c" };
		index = bus.index;
		^busArg = if(numChannels == 1) {
			prefix ++ index
		} {
			{ |i| prefix ++ (index + i) }.dup(numChannels)
		}
	}

	asMap {
		^busArg;
	}

	asUGenInput {
		^bus.index;
	}

	index {
		^bus.index;
	}

	play {
		bus.play;
	}
}