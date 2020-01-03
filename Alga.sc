/*
THINGS TO DO:

1) Create all the interpolationProxies for every param AT AlgaNodeProxy instantiation (in the "put" function)

2) Supernova ParGroups used by default in Patterns?

3) Multiple servers connection.
   (boot servers with a lot of I/O and stream bettween them, with a block size difference OFC).
   (Also, servers must be linked in Jack)

X) Make "Restoring previous connections!" actually work

X) Make SURE that all connections work fine, ensuring that interpolationProxies are ALWAYS before the modulated
proxy and after the modulator. This gets screwed up with long chains.

X) When using clear / free, interpolationProxies should not fade

*/

//From https://github.com/cappelnord/BenoitLib/blob/master/patterns/Pkr.sc
PAlgakr : Pfunc {
	*new {
		arg bus;

		var check;
		var last = 0.0;

		bus = bus.asBus;

		// audio?
		bus.isSettable.not.if {
			"Not a kr Bus or NodeProxy. This will only yield 0".warn;
			^Pfunc({0});
		};

		check = {bus.server.hasShmInterface}.try;

		check.if ({
			^Pfunc({bus.getSynchronous()});
		}, {
			"No shared memory interface detected. Use localhost server on SC 3.5 or higher to get better performance".warn;
			bus.get({|v| last = v;});
			^Pfunc({bus.get({|v| last = v;}); last;});
		});
	}
}

//Just as TempoBusClock, but with slaves for multiple servers
AlgaTempoBusClock : TempoBusClock {

	//Slaves' tempo proxy functions
	var <>slavesControl;

	init {
		arg tempo, beats, seconds, queueSize;

		//Init clock, actually:
		super.init(tempo, beats, seconds, queueSize);

		//Init dictionary
		slavesControl = IdentityDictionary.new;

		^this;
	}

	//Called when changing tempo
	setTempoAtBeat { | newTempo, beats |
		slavesControl.do({
			arg slaveControl;

			if(slaveControl.numChannels != nil, {
				slaveControl.set(\fadeTime, 0.0, \tempo, newTempo);
			}, {
				//It's been deleted from its parent ProxySpace, remove it from array
				slavesControl.removeAt(slaveControl);
			});
		});

		control.set(\fadeTime, 0.0, \tempo, newTempo);

		^super.setTempoAtBeat(newTempo, beats)
	}

	//Called when changing tempo
	setTempoAtSec { | newTempo, secs |
		slavesControl.do({
			arg slaveControl;

			if(slaveControl.numChannels != nil, {
				slaveControl.set(\fadeTime, 0.0, \tempo, newTempo);
			}, {
				//It's been deleted from its parent ProxySpace, remove it from array
				slavesControl.removeAt(slaveControl);
			});
		});

		control.set(\fadeTime, 0.0, \tempo, newTempo);

		^super.setTempoAtSec(newTempo, secs)
	}
}

AlgaProxySpace : ProxySpace {

	/*

	makeProxy {

		//AlgaProxySpace's default is a one channel audio proxy
		var proxy = AlgaNodeProxy.new(server, \audio, 1);

		this.initProxy(proxy);

		//Change reshaping to be elastic by default
		proxy.reshaping = \elastic;

		^proxy
	}

	*/

	makeProxy {
		var proxy = AlgaNodeProxy.new(server);

		this.initProxy(proxy);

		//Change reshaping to be elastic by default
		proxy.reshaping = \elastic;

		^proxy
	}

	makeMasterClock { | tempo = 1.0, beats, seconds |
		var clock, proxy;
		proxy = envir[\tempo];
		if(proxy.isNil) { proxy = AlgaNodeProxy.control(server, 1); envir.put(\tempo, proxy); };
		proxy.fadeTime = 0.0;
		proxy.put(0, { |tempo = 1.0| tempo }, 0, [\tempo, tempo]);
		this.clock = AlgaTempoBusClock.new(proxy, tempo, beats, seconds).permanent_(true);
		if(quant.isNil) { this.quant = 1.0 };
	}

	makeSlaveClock { | masterProxySpace |
		var masterClock, proxy, tempo;

		if(masterProxySpace.class != AlgaProxySpace, {
			"A AlgaProxySpace is required as a master proxy space".warn;
			^nil;
		});

		masterClock = masterProxySpace.clock;

		if(masterClock.class != AlgaTempoBusClock, {
			"A AlgaProxySpace with a running AlgaTempoBusClock is required".warn;
			^nil;
		});

		tempo = masterClock.tempo;

		proxy = envir[\tempo];
		if(proxy.isNil) { proxy = AlgaNodeProxy.control(server, 1); envir.put(\tempo, proxy); };
		proxy.fadeTime = 0.0;
		proxy.put(0, { |tempo = 1.0| tempo }, 0, [\tempo, tempo]);

		//Add slave control to this ProxySpace's ~tempo proxy
		masterClock.slavesControl.put(proxy, proxy);

		//Set tempo and quant
		this.clock = masterClock;
		this.quant = masterProxySpace.quant;
	}

	clear { |fadeTime|
		//Call ProxySpace's clear
		super.clear;

		//Remove this AlgaProxySpace from Clock's slaves
		if(this.clock.class == AlgaTempoBusClock, {
			this.clock.slavesControl.removeAt(this.envir[\tempo]);
		});
	}

	ft_ { | dur |
		this.fadeTime_(dur);
	}

	ft {
		^this.fadeTime;
	}
}

//Alias
APSpace : AlgaProxySpace {

}

AlgaProxyBlock {

	//all the proxies for this block
	var <>dictOfProxies;

	//the ordered array of proxies for the block
	var <>orderedArray;

	//A dict storing proxy -> (true or false) to state if all inputs have been checked or not
	var <>statesDict;

	//Counter for correct ordering of entries in orderedArray
	var <>runningIndex;

	//bottom most and top most proxies in this block
	var <>bottomOutProxies, <>topInProxies;

	//if the block has changed form (new proxies added, etc...)
	var <>changed = true;

	//the index for this block in the AlgaBlocksDict global dict
	var <>blockIndex;

	*new {
		arg inBlockIndex;
		^super.new.init(inBlockIndex);
	}

	init {
		arg inBlockIndex;

		this.blockIndex = inBlockIndex;

		dictOfProxies    = IdentityDictionary.new(20);
		statesDict       = Dictionary.new(20);
		bottomOutProxies = IdentityDictionary.new;
		topInProxies     = IdentityDictionary.new;
	}

	addProxy {
		arg proxy, addingInRearrangeBlockLoop = false;

		this.dictOfProxies.put(proxy, proxy);

		if(proxy.blockIndex != this.blockIndex, {

			("blockIndex mismatch detected. Using " ++ this.blockIndex).warn;
			proxy.blockIndex = this.blockIndex;

			//Also update statesDict and add one more entry to ordered array
			if(addingInRearrangeBlockLoop, {
				this.statesDict.put(proxy, false);

				this.orderedArray.add(nil);
			});

		});

		//this.changed = true;
	}

	removeProxy {
		arg proxy;

		var proxyBlockIndex = proxy.blockIndex;

		if(proxyBlockIndex != this.blockIndex, {
			"Trying to remove a proxy from a block that did not contain it!".warn;
			^nil;
		});

		this.dictOfProxies.removeAt(proxy);

		//Remove this block from AlgaBlocksDict if it's empty!
		if(this.dictOfProxies.size == 0, {
			AlgaBlocksDict.blocksDict.removeAt(proxyBlockIndex);
		});

		//this.changed = true;
	}

	rearrangeBlock {
		arg server;

		//Only rearrangeBlock when new connections have been done... It should check for inner connections,
		//not general connections though... It should be done from NodeProxy's side.
		//if(this.changed == true, {

			//ordered collection
			this.orderedArray = Array.newClear(dictOfProxies.size);

			//dictOfProxies.postln;

			("Reordering proxies for block number " ++ this.blockIndex).warn;

			//this.orderedArray.size.postln;

			//Find the proxies with no outProxies (so, the last ones in the chain!), and init the statesDict
			this.findBottomMostOutProxiesAndInitStatesDict;

			//"Block's bottomOutProxies: ".postln;
			//this.bottomOutProxies.postln;

			//"Block's statesDict: ".postln;
			//this.statesDict.postln;

			//this.orderedArray.postln;

			//init runningIndex
			this.runningIndex = 0;

			//Store the rearranging results in this.orderedArray
			this.bottomOutProxies.do({
				arg proxy;

				this.rearrangeBlockLoop(proxy); //start from index 0
			});

			//"Block's orderedArray: ".postln;
			//this.orderedArray.postln;

			//Actual ordering of groups. Need to be s.bind so that concurrent operations are synced together!
			//Routine.run({

			//server.sync;

			//this.orderedArray.postln;

			//this.dictOfProxies.postln;

			this.sanitizeArray;

			//server.bind allows here to be sure that this bundle will be sent in any case after
			//the NodeProxy creation bundle for interpolation proxies.
			if(orderedArray.size > 0, {
				server.bind({

					var sizeMinusOne = orderedArray.size - 1;

					//First one here is the last in the chain.. I think this should actually be done for each
					//bottomOutProxy...
					var firstProxy = orderedArray[0];


					//is proxy playing?



					//Must loop reverse to correct order stuff
					sizeMinusOne.reverseDo({
						arg index;

						var count = index + 1;

						var thisEntry = orderedArray[count];
						var prevEntry = orderedArray[count - 1];

						prevEntry.beforeMoveNextInterpProxies(thisEntry);

						(prevEntry.asString ++ " before " ++ thisEntry.asString).postln;

						//thisEntry.class.postln;
						//prevEntry.class.postln;


					});

					//Also move first one (so that its interpolationProxies are correct)
					if(firstProxy != nil, {
						firstProxy.before(firstProxy);
					});
				});
			});

			//REVIEW THIS:
			//this.changed = false;

			//}, 1024);

		//});

		//"BEFORE".postln;
		//this.dictOfProxies.postln;
		//this.orderedArray.postln;

		//Remove all the proxies that were not used in the connections
		this.sanitizeDict;

		//"AFTER".postln;
		//this.dictOfProxies.postln;
		//this.orderedArray.postln;

	}

	//Remove nil entries, coming from mistakes in adding/removing elements to block.
	//Also remove entries that have no outProxies and are not playing to output. This
	//is needed for a particular case when switching proxies connection to something that
	//is playing to output, the ordering could bring the previous playing proxy after the
	//one that is playing out, not accounting if there was fadetime, generating click.
	sanitizeArray {
		//this.orderedArray.removeEvery([nil]);

		this.orderedArray.removeAllSuchThat({

			arg item;

			var removeCondition;

			//If nil, remove entry anyway. Otherwise, look for the other cases.
			if(item == nil, {
				removeCondition = true;
			}, {
				removeCondition = (item.isMonitoring == false).and(item.outProxies.size == 0);
			});

			removeCondition;

		});

	}

	//Remove non-used entries and set their blockIndex back to -1
	sanitizeDict {

		if(this.orderedArray.size > 0, {
			this.dictOfProxies = this.dictOfProxies.select({
				arg proxy;
				var result;

				block ({
					arg break;

					this.orderedArray.do({
						arg proxyInArray;
						result = proxy == proxyInArray;

						//Break on true, otherwise keep searching.
						if(result, {
							break.(nil);
						});
					});
				});

				//Reset blockIndex too
				if(result.not, {
					("Removing proxy: " ++ proxy.asString ++ " from block number " ++ this.blockIndex).warn;
					proxy.blockIndex = -1;
				});

				result;

			});
		}, {

			//Ordered array has size 0. Free all

			this.dictOfProxies.do({
				arg proxy;
				proxy.blockIndex = -1;
			});

			this.dictOfProxies.clear;
		});

	}

	//Have something to automatically remove Proxies that haven't been touched from the dict
	rearrangeBlockLoop {
		arg proxy;

		if(proxy != nil, {

			var currentState;

			//If for any reason the proxy wasn't already in the dictOfProxies, add it
			this.addProxy(proxy, true);

			currentState = statesDict[proxy];

			//If this proxy has never been touched, avoids repetition
			if(currentState == false, {

				//("inProxies to " ++  proxy.asString ++ " : ").postln;

				proxy.inProxies.doProxiesLoop ({
					arg inProxy;

					//rearrangeInputs to this, this will add the inProxies
					this.rearrangeBlockLoop(inProxy);
				});

				//Add this
				this.orderedArray[runningIndex] = proxy;

				//Completed: put it to true so it's not added again
				statesDict[proxy] = true;

				//Advance counter
				this.runningIndex = this.runningIndex + 1;
			});
		});
	}

	findBottomMostOutProxiesAndInitStatesDict {
		this.bottomOutProxies.clear;
		this.statesDict.clear;

		this.dictOfProxies.do({
			arg proxy;

			//Find the ones with no outProxies but at least one inProxy
			if((proxy.outProxies.size == 0).and(proxy.inProxies.size > 0), {
				this.bottomOutProxies.put(proxy, proxy);
			});

			//init statesDict for all proxies to false
			this.statesDict.put(proxy, false);

		});
	}

}

//Have a global one, so that NodeProxies can be shared across VNdef, VNProxy and VPSpace...
AlgaBlocksDict {
	classvar< blocksDict;

	*initClass {
		blocksDict = Dictionary.new(50);
	}

	*reorderBlock {
		arg blockIndex, server;

		var entryInBlocksDict = blocksDict[blockIndex];

		if(entryInBlocksDict != nil, {
			entryInBlocksDict.rearrangeBlock(server);
		//}, {
		//	"Invalid block index".warn;
		});

	}

}

AlgaNodeProxy : NodeProxy {

	classvar <>defaultAddAction = \addToTail;
	classvar <>defaultReshaping = \elastic;   //Use \elasitc as default. It's set in NodeProxy's init (super.init)

	//The block index that contains this proxy
	var <>blockIndex = -1;

	var <>isInterpProxy = false;

	var <>defaultControlNames;

	var <>interpolationProxies, <>interpolationProxiesCopy, <>inProxies, <>outProxies;

	//Add the SynthDef for ins creation at startup!
	*initClass {
		StartUp.add({

			//Generate for each num of channels up to 16:

			/*
			SynthDef(\proxyIn_ar1, {
				var fadeTimeEnv = EnvGate.new(i_level: 0, doneAction:2, curve: 'sin');
				Out.ar(\out.ir(0), \in.ar(0) * fadeTimeEnv);
			}, [\ir, \ar]).add;

			SynthDef(\proxyIn_kr1, {
				var fadeTimeEnv = EnvGate.new(i_level: 0, doneAction:2, curve: 'lin');
				Out.kr(\out.ir(0), \in.kr(0) * fadeTimeEnv);
			}).add;
			*/


			16.do({
				arg counter;

				var synthDefString_ar, synthDefString_kr, arrayOfZeros = "[";

				counter = counter + 1;

				if(counter == 1, {
					synthDefString_ar = "ProxySynthDef(\\proxyIn_ar1, {\\in.ar(0)}).add;";
					synthDefString_kr = "ProxySynthDef(\\proxyIn_kr1, {\\in.kr(0)}).add;";
				}, {

					//Generate [0, 0, 0, ...
					counter.do({
						arrayOfZeros = arrayOfZeros ++ "0,"
					});

					//remove trailing coma [0, 0, 0, and enclose in bracket -> [0, 0, 0]
					arrayOfZeros = arrayOfZeros[0..(arrayOfZeros.size - 2)] ++ "]";

					synthDefString_ar = "ProxySynthDef(\\proxyIn_ar" ++ counter.asString ++ ", {\\in.ar(" ++ arrayOfZeros ++ ")}).add;";
					synthDefString_kr = "ProxySynthDef(\\proxyIn_kr" ++ counter.asString ++ ", {\\in.kr(" ++ arrayOfZeros ++ ")}).add;";
				});


				//Evaluate the generated code
				synthDefString_ar.interpret;
				synthDefString_kr.interpret;
			});

			});
	}

	init {
		//These are the interpolated ones!!
		interpolationProxies = IdentityDictionary.new;

		//These are used for <| (unmap) to restore default values and to get number of channels per parameter
		defaultControlNames = Dictionary.new;

		//General I/O
		inProxies  = IdentityDictionary.new(20);
		outProxies = IdentityDictionary.new(20);

		blockIndex = -1;

		//Call NodeProxy's init
		super.init;

		//Default reshaping is expanding
		this.reshaping = defaultReshaping;
	}

	clear { | fadeTime = 0, isInterpolationProxy = false |

		//copy interpolationProxies in new IdentityDictionary in order to free them only
		//after everything has been freed already.
		//Also, remove block from AlgaBlocksDict.blocksDict
		if(isInterpolationProxy.not, {
			var blockWithThisProxy;

			interpolationProxies.postln;

			interpolationProxiesCopy = interpolationProxies.copy;

			//remove from block in AlgaBlocksDict.blocksDict
			blockWithThisProxy = AlgaBlocksDict.blocksDict[this.blockIndex];

			if(blockWithThisProxy != nil, {
				blockWithThisProxy.removeProxy(this);
			});
		});

		//This will run through before anything.. that's why the copies
		this.free(fadeTime, true, isInterpolationProxy); 	// free group and objects

		//Remove all connected inProxies
		inProxies.keysValuesDo({
			arg param, proxy;

			if(proxy.class != Array, {
				//Remove the outProxy entry in the connected proxies
				proxy.outProxies.removeAt(param);
			}, {
				/*
				//Function, Binops, Arrays
				proxy.do({
					arg proxyInArray;
					proxyInArray.outProxies.removeAt(param);
				})
				*/
			});


		});

		//Remove all connected outProxies
		outProxies.keysValuesDo({
			arg param, proxy;

			//Remove the inProxy entry in the connected proxies
			proxy.inProxies.removeAt(param);
		});

		//Remove all NodeProxies used for param interpolation!!
		//(interpolationProxies don't have other interpolation proxies, don't need to run this:)
		if(isInterpolationProxy.not, {

			if(fadeTime == nil, {fadeTime = 0});

			Routine.run({

				(fadeTime + 0.001).wait;

				//"Clearing interp proxies".postln;

				//interpolationProxiesCopy.postln;

				interpolationProxiesCopy.do({
					arg proxy;
					proxy.clear(0, true, true);
				});

				//Only clear at the end of routine
				interpolationProxiesCopy.clear; interpolationProxiesCopy = nil;

			});
		});

		this.removeAll; 			// take out all objects

		children = nil;             // for now: also remove children

		this.stop(fadeTime, true);		// stop any monitor

		monitor = nil;

		this.fadeTime = fadeTime; // set the fadeTime one last time for freeBus
		this.freeBus;	 // free the bus from the server allocator

		//Reset
		inProxies.clear; inProxies  = nil;
		outProxies.clear; outProxies = nil;
		defaultControlNames.clear; defaultControlNames = nil;
		interpolationProxies.clear; interpolationProxies = nil;

		this.blockIndex = -1;

		this.init;	// reset the environment
		this.changed(\clear, [fadeTime]);
	}

	free { | fadeTime = 0, freeGroup = true, isInterpolationProxy = false |
		var bundle, freetime;
		var oldGroup = group;
		if(this.isPlaying) {
			bundle = MixedBundle.new;
			if(fadeTime.notNil) {
				bundle.add([15, group.nodeID, "fadeTime", fadeTime]) // n_set
			};
			this.stopAllToBundle(bundle, fadeTime);
			if(freeGroup) {
				oldGroup = group;
				group = nil;
				freetime = (fadeTime ? this.fadeTime) + (server.latency ? 0) + 1e-9; // delay a tiny little
				server.sendBundle(freetime, [11, oldGroup.nodeID]); // n_free
			};
			bundle.send(server);
			this.changed(\free, [fadeTime, freeGroup]);
		};

		//interpolationProxies don't have other interpolationProxies, no need to run this.
		if(isInterpolationProxy.not, {

			//If just running free without clear, this hasn't been copied over
			if(interpolationProxiesCopy.size != interpolationProxies.size, {
				interpolationProxiesCopy = interpolationProxies.copy;
			});

			if(fadeTime == nil, {fadeTime = 0});

			Routine.run({
				(fadeTime + 0.001).wait;

				//"Freeing interp proxies".postln;

				interpolationProxiesCopy.do({
					arg proxy;
					proxy.free(0, freeGroup, true);
				});
			});
		});

	}

	fadeTime_ { | dur |
		if(dur.isNil) { this.unset(\fadeTime) } { this.set(\fadeTime, dur) };

		//fadeTime_ also applies to interpolated input proxies...
		//This should only be set for ProxySpace stuff, not in general to be honest...
		interpolationProxies.do({
			arg proxy;
			proxy.fadeTime = dur;
		});
	}

	ft_ { | dur |
		this.fadeTime_(dur);
	}

	ft {
		^this.fadeTime
	}

	params {
		^this.interpolationProxies;
	}

	//Copied over from NodeProxy with added defaultControlNames
	putExtended { | index, obj, channelOffset = 0, extraArgs, now = true |
		var container, bundle, oldBus = bus;

		if(obj.isNil) { this.removeAt(index); ^this };
		if(index.isSequenceableCollection) {
			^this.putAll(obj.asArray, index, channelOffset)
		};

		bundle = MixedBundle.new;

		container = obj.makeProxyControl(channelOffset, this);
		container.build(this, index ? 0); // bus allocation happens here

		//Need this to retrieve default values and number of channels per parameter
		if(isInterpProxy == false, {
			container.controlNames.do({
				arg controlName;

				var controlNameName = controlName.name;

				if((controlNameName != \gate).and(
					controlNameName != \out).and(
					controlNameName != \fadeTime), {

					defaultControlNames.put(controlNameName, controlName);

				});
			});
		});

		if(this.shouldAddObject(container, index)) {
			// server sync happens here if necessary
			if(server.serverRunning) { container.loadToBundle(bundle, server) } { loaded = false; };
			this.prepareOtherObjects(bundle, index, oldBus.notNil and: { oldBus !== bus });
		} {
			format("failed to add % to node proxy: %", obj, this).postln;
			^this
		};

		this.putNewObject(bundle, index, container, extraArgs, now);
		this.changed(\source, [obj, index, channelOffset, extraArgs, now]);

	}

	//When a new object is assigned to a AlgaNodeProxy!
	put { | index, obj, channelOffset = 0, extraArgs, now = true |

		var numberOfChannels;

		var isObjAFunction, isObjAnOp, isObjAnArray;

		//Call NodeProxy's put, first.
		//super.put(index, obj, channelOffset, extraArgs, now);
		this.putExtended(index, obj, channelOffset, extraArgs, now);

		//Create interpolationProxies for all params
		if(isInterpProxy == false, {

			this.createAllInterpProxies;

			//Different cases!

			//Function:
			//~c = {~a * 0.5}, ensuring ~a is before ~c
			isObjAFunction = obj.class == Function;

			//Binary/Unary ops:
			//~c = ~a * 0.5, ensuring ~a is before ~c
			isObjAnOp = obj.class.superclass == AbstractOpPlug;

			//Array:
			//~c = [~a, ~b], ensuring ~a and ~b are before ~c
			isObjAnArray = obj.class == Array;

			/*
			//Free previous entries in the indices slots
			if(index == nil, {

			//Free all previous connected proxies, if there were any...
			this.inProxies.keysValuesDo({
			arg param, proxy;

			//This will consider all indices.
			if(param.asString.beginsWith("___SPECIAL_ASSIGNMENT___"), {

			//proxy is going to be an array
			proxy.do({
			arg proxyArrayEntry;
			proxyArrayEntry.outProxies.removeAt(this);
			});

			this.inProxies.removeAt(param);
			});
			});

			}, {

			//Free previous connected proxy at index
			this.inProxies.keysValuesDo({
			arg param, proxy;

			//This will consider the correct iindex
			if(param == (\___SPECIAL_ASSIGNMENT___ ++ index.asSymbol), {

			//proxy is going to be an array
			proxy.do({
			arg proxyArrayEntry;
			proxyArrayEntry.outProxies.removeAt(this);
			});

			this.inProxies.removeAt(param);

			});
			});

			});

			if((isObjAFunction).or(isObjAnOp).or(isObjAnArray), {

			//Special overloaded function for Function, AbstractOpPlug and Array
			//which takes care of proper ordering the proxies
			obj.putObjBefore(this, index);

			});

			*/

			////////////////////////////////////////////////////////////////

			//REARRANGE BLOCK!!

			AlgaBlocksDict.reorderBlock(this.blockIndex, server);

			//////////////////////////////////////////////////////////////
		});
	}

	//Start group if necessary. Here is the defaultAddAction at work.
	//This function is called in put -> putNewObject
	prepareToBundle { arg argGroup, bundle, addAction = defaultAddAction;
		super.prepareToBundle(argGroup, bundle, addAction);
	}

	//These are straight up copied from BusPlug. Overwriting to retain group ordering stuff
	play { | out, numChannels, group, multi=false, vol, fadeTime, addAction |
		var bundle = MixedBundle.new;
		if(this.homeServer.serverRunning.not) {
			("server not running:" + this.homeServer).warn;
			^this
		};
		if(bus.rate == \control) { "Can't monitor a control rate bus.".warn; monitor.stop; ^this };
		group = group ?? {this.homeServer.defaultGroup};
		this.playToBundle(bundle, out, numChannels, group, multi, vol, fadeTime, addAction);
		// homeServer: multi client support: monitor only locally
		bundle.schedSend(this.homeServer, this.clock ? TempoClock.default, this.quant);

		////////////////////////////////////////////////////////////////

		//REARRANGE BLOCK!!
		AlgaBlocksDict.reorderBlock(this.blockIndex, server);

		////////////////////////////////////////////////////////////////

		/*
		//Add defaultAddAction
		if(addAction == nil, {
			addAction = defaultAddAction;
		});
		*/

		this.changed(\play, [out, numChannels, group, multi, vol, fadeTime, addAction]);
	}

	playN { | outs, amps, ins, vol, fadeTime, group, addAction |
		var bundle = MixedBundle.new;
		if(this.homeServer.serverRunning.not) {
			("server not running:" + this.homeServer).warn;
			^this
		};
		if(bus.rate == \control) { "Can't monitor a control rate bus.".warn; monitor.stop; ^this };
		group = group ?? {this.homeServer.defaultGroup};
		this.playNToBundle(bundle, outs, amps, ins, vol, fadeTime, group, addAction);
		bundle.schedSend(this.homeServer, this.clock ? TempoClock.default, this.quant);

		////////////////////////////////////////////////////////////////

		//REARRANGE BLOCK!!

		AlgaBlocksDict.reorderBlock(this.blockIndex, server);

		////////////////////////////////////////////////////////////////

		/*
		//Add defaultAddAction
		if(addAction == nil, {
			addAction = defaultAddAction;
		});
		*/

		this.changed(\playN, [outs, amps, ins, vol, fadeTime, group, addAction]);
	}

	connectToInterpProxy {
		//Pass interpolationProxy as argument to save CPU cycles of retrieving it from dict
		arg param = \in, interpolationProxy = nil, proxy;

		var controlName, rate, isProxyAProxy, numChannels, canBeMapped;

		isProxyAProxy = (proxy.class == AlgaNodeProxy).or(
			proxy.class.superclass == AlgaNodeProxy).or(
			proxy.class.superclass.superclass == AlgaNodeProxy);

		if(proxy.isNil) { ^this.unmap(param) };

		controlName = this.defaultControlNames[param];

		if(controlName == nil, {
			("ERROR: Could not find param " ++ param).warn;
			^proxy;
		});

		//If nil, try to retrieve it from the dict
		if(interpolationProxy == nil, {

			interpolationProxy = this.interpolationProxies[param];

			//If still nil, exit
			if(interpolationProxy == nil, {
				("ERROR: Could not find interpolationProxy for " ++ param).warn;
				^proxy;
			});
		});

		rate = controlName.rate;

		//numChannels set according to proxy if proxy is a proxy, otherwise they are set according to
		//interpolationProxies' parameter spec if not a proxy
		if(isProxyAProxy, {
			numChannels = proxy.numChannels;
		}, {
			numChannels = controlName.numChannels;
		});

		//warning: proxy should still have a fixed bus
		canBeMapped = proxy.initBus(rate, numChannels);

		/*
		"connectToInterpProxy".postln;
		proxy.asString.postln;
		proxy.numChannels.postln;

		if(numChannels != proxy.numChannels, {
			("Channel mismatch. Input proxy has " ++ proxy.numChannels.asString ++
			", while parameter \"" ++ param.asString ++ " \" has " ++ numChannels.asString).warn;
		});
		*/

		if(canBeMapped) {
			if(interpolationProxy.isNeutral) { interpolationProxy.defineBus(rate, numChannels) };
			interpolationProxy.xset(\in, proxy);
		} {
			"Could not link node proxies, no matching input found.".warn
		};

		^proxy // returns first argument for further chaining
	}

	//Same as <<> but uses .xset instead of .xmap.
	connectXSet { | proxy, key = \in |
		var controlName, rate, numChannels, canBeMapped;

		if(proxy.isNil) { ^this.unmap(key) };

		controlName = this.defaultControlNames[key];

		if(controlName != nil, {
			rate = controlName.rate;

			numChannels = controlName.numChannels;

			canBeMapped = proxy.initBus(rate, numChannels); // warning: proxy should still have a fixed bus

			("ConnectXSet : " ++ this.asString ++ " from " ++ proxy.asString ++ " at " ++ key.asString).postln;
			rate.postln;

			if(canBeMapped) {
				if(this.isNeutral) { this.defineBus(rate, numChannels) };
				this.xset(key, proxy);
			} {
				"Could not link node proxies, no matching input found.".warn
			};

		}, {
			("ERROR: Could not find param " ++ key).warn;
		});

		^proxy // returns first argument for further chaining
	}

	//Same as <<> but uses .set instead of .xmap.
	connectSet { | proxy, key = \in |
		var controlName, rate, numChannels, canBeMapped;

		if(proxy.isNil) { ^this.unmap(key) };

		controlName = this.defaultControlNames[key];

		if(controlName != nil, {
			rate = controlName.rate;

			numChannels = controlName.numChannels;

			canBeMapped = proxy.initBus(rate, numChannels); // warning: proxy should still have a fixed bus

			("ConnectXSet : " ++ this.asString ++ " from " ++ proxy.asString ++ " at " ++ key.asString).postln;
			rate.postln;

			if(canBeMapped) {
				if(this.isNeutral) { this.defineBus(rate, numChannels) };
				this.set(key, proxy);
			} {
				"Could not link node proxies, no matching input found.".warn
			};

		}, {
			("ERROR: Could not find param " ++ key).warn;
		});

		^proxy // returns first argument for further chaining
	}

	createAllInterpProxies {

		server.bind({

			defaultControlNames.do({
				arg controlName;

				var paramName = controlName.name;
				var paramVal  = controlName.defaultValue;

				var paramNumberOfChannels = controlName.numChannels;

				//Retrieve the original default value, used to restore things when unmapping ( <| )
				//this.defaultParamsVals.put(paramName, paramVal);

				//paramVal.postln;
				//paramNumberOfChannels.postln;

				//Create interpProxy for this paramName
				this.createInterpProxy(paramName, controlName, paramNumberOfChannels);

			});

		});

		//this.defaultControlNames.postln;
		//this.interpolationProxies.postln;
	}

	createInterpProxy {
		arg paramName = \in, controlName, paramNumberOfChannels = 1, src = nil;

		var paramRate;

		var isThisProxyInstantiated = true;

		var prevInterpProxy;

		var interpolationProxy;

		if(this.group == nil, {
			("This proxy hasn't been instantiated yet!!!").warn;
			isThisProxyInstantiated = false;
		});

		if(controlName != nil, {
			paramRate = controlName.rate;
		}, {
			("Can't retrieve parameter rate for " ++ paramName).postln;
			^nil;
		});

		//Check if interpolationProxy was already created.
		prevInterpProxy = this.interpolationProxies[paramName];

		//this.interpolationProxies.postln;

		if(prevInterpProxy == nil, {

			var defaultValue = controlName.defaultValue;

			//Doesn't work with Pbinds with ar param, would just create a kr version
			if(paramRate == \audio, {
				interpolationProxy = AlgaNodeProxy.new(server, \audio,   paramNumberOfChannels);
			}, {
				interpolationProxy = AlgaNodeProxy.new(server, \control, paramNumberOfChannels);
			});

			interpolationProxy.isInterpProxy = true;

			//Should it not be elastic?
			interpolationProxy.reshaping = defaultReshaping;

			//Default fadeTime: use nextProxy's (the modulated one) fadeTime
			interpolationProxy.fadeTime = this.fadeTime;

			//Add the new interpolation NodeProxy to interpolationProxies dict
			this.interpolationProxies.put(paramName, interpolationProxy);

			interpolationProxy.outProxies.put(paramName, this);

			//This routine stuff needs to be tested on Linux...
			Routine.run({

				//Initialize the value
				if(paramRate == \audio, {
					var proxyInSymbol = ("proxyIn_ar" ++ paramNumberOfChannels).asSymbol;
					proxyInSymbol.postln;
					interpolationProxy.source = proxyInSymbol;
				}, {
					var proxyInSymbol = ("proxyIn_kr" ++ paramNumberOfChannels).asSymbol;
					proxyInSymbol.postln;
					interpolationProxy.source = proxyInSymbol;
				});

				//sync server so group is correctly created for interpolationProxy
				server.sync;

				//Assign the defaultValue to the interpolationProxy
				interpolationProxy.set(\in, defaultValue);

				//this.connectSet(interpolationProxy, paramName);
				//Connect the interpolationProxy to the correct param
				this.set(paramName, interpolationProxy);

			});
		}, {

			("Already Existing Param, " ++ paramName).warn;

			if(paramRate == \audio, {
				var proxyInSymbol = ("proxyIn_ar" ++ paramNumberOfChannels).asSymbol;
				proxyInSymbol.postln;
				prevInterpProxy.source = proxyInSymbol;
			}, {
				var proxyInSymbol = ("proxyIn_kr" ++ paramNumberOfChannels).asSymbol;
				proxyInSymbol.postln;
				prevInterpProxy.source = proxyInSymbol;
			});

		});
	}

	setInterpProxy {
		arg prevProxy, param = \in, src = nil;

		//Check if there already was an interpProxy for the parameter
		var interpolationProxyEntry = this.interpolationProxies[param];

		//Returns nil with a Pbind.. this could be problematic for connections, rework it!
		var paramRate;

		var controlName;

		var numberOfChannels;

		var isThisProxyInstantiated = true;
		var isPrevProxyInstantiated = true;

		//This is the connection that is in place with the interpolation NodeProxy.
		var previousParamEntry = this.inProxies[param];

		//This is used to discern the different behaviours
		var prevProxyClass = prevProxy.class;

		var isPrevProxyAProxy = (prevProxyClass == AlgaNodeProxy).or(
			prevProxyClass.superclass == AlgaNodeProxy).or(
			prevProxyClass.superclass.superclass == AlgaNodeProxy);

		/*
		var isPrevProxyANumber = false;

		if(isPrevProxyAProxy.not, {
			isPrevProxyANumber = (prevProxyClass == Number).or(
				prevProxyClass.superclass == Number).or(
				prevProxyClass.superclass.superclass == Number);
		});
		*/


		if(this.group == nil, {
			("This proxy hasn't been instantiated yet!!!").warn;
			isThisProxyInstantiated = false;

			//^this;
		});

		if(isPrevProxyAProxy, {
			if(prevProxy.group == nil, {
				("prevProxy hasn't been instantiated yet!!!").warn;
				isPrevProxyInstantiated = false;

				//^this;
			});
		});

		controlName = defaultControlNames[param];

		if(controlName != nil, {
			paramRate = controlName.rate;
		}, {
			("Can't retrieve parameter rate for " ++ param).postln;
			^nil;
		});

		if(isPrevProxyAProxy, {
			numberOfChannels = controlName.numChannels;
		});

		//Retrieved from the default value!
		//numberOfChannels = this.defaultParamsVals[param].size;
		//if(numberOfChannels < 1, { numberOfChannels = 1; });

		//Free previous connections to the this, if there were any
		this.freePreviousConnection(param);

		//Just switch the function
		//if(src != nil, {
		//	interpolationProxyEntry.source = src;
		//});

		//If changing the connections with a new NodeProxy
		//if(paramEntryInInProxiesIsPrevProxy.not, {
		if(previousParamEntry != prevProxy, {

			//Previous interpProxy
			var interpolationProxySource = interpolationProxyEntry.source;

			//interpolationProxySource.postln;

			//Don't use param indexing for outs, as this proxy could be linked
			//to multiple proxies with same param names
			if(isPrevProxyAProxy, {
				this.inProxies.put(param, prevProxy);
				prevProxy.outProxies.put(this, this);
			});

			//re-instantiate source if not correct, could have been modified by Binops, Function, array
			if(interpolationProxySource.asString.beginsWith("\proxyIn").not, {
				if(paramRate == \audio, {
					var proxyInSymbol = ("proxyIn_ar" ++ numberOfChannels).asSymbol;
					proxyInSymbol.postln;

					interpolationProxyEntry.source = proxyInSymbol;
				}, {
					var proxyInSymbol = ("proxyIn_kr" ++ numberOfChannels).asSymbol;
					proxyInSymbol.postln;

					interpolationProxyEntry.source = proxyInSymbol;
				});
			});

			//interpolationProxyEntry.outProxies remains the same, connected to this!
			if(isPrevProxyAProxy, {
				interpolationProxyEntry.inProxies.put(\in, prevProxy);
			});

			//Only rearrange block if both proxies are actually instantiated.
			if(isThisProxyInstantiated.and(isPrevProxyInstantiated), {
				AlgaBlocksDict.reorderBlock(this.blockIndex, server);
			});

			//interpolationProxyEntry.connectXSet(prevProxy, \in);

			/*
			"setInterpProxy".postln;
			prevProxy.asString.postln;
			prevProxy.numChannels.postln;
			*/

			//Make connection to the interpolationProxy
			this.connectToInterpProxy(param, interpolationProxyEntry, prevProxy);
		});
	}

	//Combines before with <<>
	=> {
		arg nextProxy, param = \in;

		var isNextProxyAProxy, isThisProxyAnOp, isThisProxyAFunc, isThisProxyAnArray;

		var thisBlockIndex;
		var nextProxyBlockIndex;

		isNextProxyAProxy = (nextProxy.class == AlgaNodeProxy).or(
			nextProxy.class.superclass == AlgaNodeProxy).or(
			nextProxy.class.superclass.superclass == AlgaNodeProxy);

		if((isNextProxyAProxy.not), {
			"nextProxy is not a valid AlgaNodeProxy!!!".warn;
			^this;
		});

		if(this.server != nextProxy.server, {
			"nextProxy is on a different server!!!".warn;
			^this;
		});

		//("nextProxy's num of channels: " ++ nextProxy.numChannels).postln;

		/*
		//Different cases:
		//Binary / Unary operators:
		//~b = ~a * 0.1
		//~b => ~c
		isThisProxyAnOp = (this.source.class.superclass == AbstractOpPlug);
		if(isThisProxyAnOp, {

			//Run the function from the overloaded functions in ClassExtensions.sc
			^this.source.perform('=>', nextProxy, param);
		});

		//DON'T RUN Function's as this.source will always be a function anyway.
		//It would overwrite standard casese like:
		//~saw = {Saw.ar(\f.kr(100))}
		//~lfo = {SinOsc.kr(1).range(1, 10)}
		//~lfo =>.f ~saw
		//~lfo.source here is a function!! I don't want to overwrite that

		//Array:
		//~a = [~lfo1, ~lfo2]
		//~a => b
		isThisProxyAnArray = (this.source.class == Array);
		if(isThisProxyAnArray, {

			//Run the function from the overloaded functions in ClassExtensions.sc
			^this.source.perform('=>', nextProxy, param);
		});
		*/

		//Create a new block if needed
		this.createNewBlockIfNeeded(nextProxy);

		/*
		"=>".postln;
		this.asString.postln;
		this.numChannels.postln;
		*/

		//Create a new interp proxy if needed, and make correct connections
		nextProxy.setInterpProxy(this, param);

		//return nextProxy for further chaining
		^nextProxy;
	}

	//combines before (on nextProxy) with <>>
	//It also allows to set to plain numbers, e.g. ~sine <=.freq 440

	<= {
		arg nextProxy, param = \in;

		var isNextProxyAProxy, isNextProxyAnOp, isNextProxyAFunc, isNextProxyAnArray, paramRate;

		/* Overloaded calls for AbstractOpPlug, Function and Array */

		/*

		//Binary or Unary ops, e.g. ~b <= (~a * 0.5)
		isNextProxyAnOp = nextProxy.class.superclass == AbstractOpPlug;
		if(isNextProxyAnOp, {

			//Run the function from the overloaded functions in ClassExtensions.sc
			^nextProxy.perform('=>', this, param);
		});

		//Function, e.g. ~b <= {~a * 0.5}
		isNextProxyAFunc = nextProxy.class == Function;
		if(isNextProxyAFunc, {

			//Run the function from the overloaded functions in ClassExtensions.sc
			^nextProxy.source.perform('=>', this, param);
		});

		//Array, e.g. ~a <=.freq [~lfo1, ~lfo2]
		isNextProxyAnArray = nextProxy.class == Array;
		if(isNextProxyAnArray, {

			//Run the function from the overloaded functions in ClassExtensions.sc
			^nextProxy.perform('=>', this, param);
		});
		*/

		isNextProxyAProxy = (nextProxy.class == AlgaNodeProxy).or(
			nextProxy.class.superclass == AlgaNodeProxy).or(
			nextProxy.class.superclass.superclass == AlgaNodeProxy);

		/*
		What if interpolationProxies to set are an array ???
		e.g.: ~sines <=.freq [~lfo1, ~lfo2]
		*/

		//Standard case with another NodeProxy
		if(isNextProxyAProxy, {

			//If next proxy is an AbstractOpPlug or Function, check ClassExtensions.sc
			nextProxy.perform('=>', this, param);

			//Return nextProxy for further chaining
			^nextProxy;

		}, {
			//Case when nextProxy is a Number.. like ~sine <=.freq 400.
			//Can't use => as Number doesn't have => method

			//Create a new block if needed
			this.createNewBlockIfNeeded(this);

			//Free previous connections to the this, if there were any
			this.freePreviousConnection(param);

			this.setInterpProxy(nextProxy, param);

		});

		//return this for further chaining
		^this;
	}

	//Unmap
	<| {
		arg param = \in;

		var controlName = defaultControlNames[param];

		if(controlName == nil, {
			"Trying to restore a nil value".warn;
		}, {
			var defaultValue = controlName.defaultValue;

			("Restoring default value for " ++ param ++ " : " ++ defaultValue).postln;

			//Simply restore the default original value using the <= operator
			this.perform('<=', defaultValue, param);
		});

		^this;
	}

	freeAllInProxiesConnections {

		//Remove all relative outProxies
		inProxies.keysValuesDo({
			arg param, proxy;

			if(proxy.class != Array, {
				//Remove the outProxy entry in the connected proxies
				proxy.outProxies.removeAt(param);
			}, {
				//Function, Binops, Arrays
				proxy.do({
					arg proxyInArray;
					proxyInArray.outProxies.removeAt(param);
				})
			});
		});

		inProxies.clear;
	}

	freeAllOutProxiesConnections {

		//Remove all relative inProxies
		outProxies.keysValuesDo({
			arg param, proxy;

			//Remove the inProxy entry in the connected proxies
			proxy.inProxies.removeAt(param);
		});

		outProxies.clear;
	}

	freePreviousConnection {
		arg param = \in;

		//First, empty the connections that were on before (if there were any)
		var previousEntry = this.inProxies[param];

		var isPreviousEntryAProxy = (previousEntry.class == AlgaNodeProxy).or(
			previousEntry.class.superclass == AlgaNodeProxy).or(
			previousEntry.class.superclass.superclass == AlgaNodeProxy);

		if(isPreviousEntryAProxy, {
			//Remove connection in previousEntry's outProxies to this one
			previousEntry.removeOutProxy(this, param);

		}, {
			//ARRAY!

			//Array is used to store connections for Function, AbstractOpPlug and Array,
			//since multiple NodeProxies might be connected to the same param.
			var isPreviousEntryAnArray = previousEntry.class == Array;

			if(isPreviousEntryAnArray, {
				previousEntry.do({
					arg previousProxyEntry;
					previousProxyEntry.removeOutProxy(this, param);
				});
			});
		});


		//FIX HERE!
		//Remove the entry from inProxies... This fucks up things for paramEntryInInProxies
		if(previousEntry != nil, {
			this.inProxies.removeAt(param);
		});
	}

	removeOutProxy {
		arg proxyToRemove, param = \in;

		var isThisConnectedToAnotherParam = false;

		//First, check if the this was perhaps connected to another param of the other proxy..
		//This is a little to expensive, find a better way
		block ({
			arg break;
			proxyToRemove.inProxies.keysValuesDoProxiesLoop({
				arg inParam, inProxy;

				//Check for param duplicates and identity
				if((inParam != param).and(inProxy == this), {
					isThisConnectedToAnotherParam = true;
					break.(nil);
				});
			});
		});

		//this.postln;
		//proxyToRemove.postln;
		//isThisConnectedToAnotherParam.postln;

		if(isThisConnectedToAnotherParam == false, {
			//Remove older connection to this only if it's not connected to
			//any other param of this proxy..
			//Remember that outProxies are stored with proxy -> proxy, not param -> proxy
			this.outProxies.removeAt(proxyToRemove);
		});

		//Also reset block index if needed, if its outProxies
		//and inProxies have size 0 (meaning it's not connected to anything anymore)
		if((this.outProxies.size == 0).and(this.inProxies.size == 0), {
			this.blockIndex = -1;
		});
	}

	//This function should be moved to AlgaProxyBlock
	createNewBlockIfNeeded {
		arg nextProxy;

		var newBlockIndex;
		var newBlock;

		var thisBlockIndex = this.blockIndex;
		var nextProxyBlockIndex = nextProxy.blockIndex;

		"createNewBlockIfNeeded".postln;

		//Create new block if both connections didn't have any
		if((thisBlockIndex == -1).and(nextProxyBlockIndex == -1), {
			newBlockIndex = UniqueID.next;
			newBlock = AlgaProxyBlock.new(newBlockIndex);

			"new block".postln;

			this.blockIndex = newBlockIndex;
			nextProxy.blockIndex = newBlockIndex;

			//Add block to blocksDict
			AlgaBlocksDict.blocksDict.put(newBlockIndex, newBlock);

			//Add proxies to the block
			AlgaBlocksDict.blocksDict[newBlockIndex].addProxy(this);
			AlgaBlocksDict.blocksDict[newBlockIndex].addProxy(nextProxy);

		}, {

			//If they are not already in same block
			if(thisBlockIndex != nextProxyBlockIndex, {

				//Else, add this proxy to nextProxy's block, together with all proxies from this' block
				if(thisBlockIndex == -1, {
					"add this to nextProxy's block".postln;
					this.blockIndex = nextProxyBlockIndex;

					//Add proxy to the block
					AlgaBlocksDict.blocksDict[nextProxyBlockIndex].addProxy(this);

					//This is for the changed at the end of function...
					newBlockIndex = nextProxyBlockIndex;
				}, {

					//Else, add nextProxy to this block, together with all proxies from nextProxy's block
					if(nextProxyBlockIndex == -1, {
						"add nextProxy to this' block".postln;
						nextProxy.blockIndex = thisBlockIndex;

						//Add proxy to the block
						AlgaBlocksDict.blocksDict[thisBlockIndex].addProxy(nextProxy);

						//This is for the changed at the end of function...
						newBlockIndex = thisBlockIndex;
					});
				}, {

					//Else, it means bot proxies are already in blocks. Merge them into a new one!

					newBlockIndex = UniqueID.next;
					newBlock = AlgaProxyBlock.new(newBlockIndex);

					"both proxies already into blocks. creating new".postln;

					//Change all proxies' group to the new one and add then to new block
					AlgaBlocksDict.blocksDict[thisBlockIndex].dictOfProxies.do({
						arg proxy;

						proxy.blockIndex = newBlockIndex;

						newBlock.addProxy(proxy);
					});

					AlgaBlocksDict.blocksDict[nextProxyBlockIndex].dictOfProxies.do({
						arg proxy;

						proxy.blockIndex = newBlockIndex;

						newBlock.addProxy(proxy);
					});

					//Remove previous' groups
					AlgaBlocksDict.blocksDict.removeAt(thisBlockIndex);
					AlgaBlocksDict.blocksDict.removeAt(nextProxyBlockIndex);

					//Also add the two connected proxies to this new group
					this.blockIndex = newBlockIndex;
					nextProxy.blockIndex = newBlockIndex;

					//Finally, add the actual block to the dict
					AlgaBlocksDict.blocksDict.put(newBlockIndex, newBlock);
				});
			});
		});

		//If both are already into blocks and the block is different, the two blocks should merge into a new one!
		//if((thisBlockIndex != nextProxyBlockIndex).and((thisBlockIndex != -1).and(nextProxyBlockIndex != -1)), {
		//});

		//If the function pass through, pass this' blockIndex instead
		if(newBlockIndex == nil, {newBlockIndex = this.blockIndex;});

		//A new connection happened in any case! Some things might have changed in the block
		AlgaBlocksDict.blocksDict[newBlockIndex].changed = true;
	}

	//Also moves interpolation proxies
	after {
		arg nextProxy;

		this.group.moveAfter(nextProxy.group);

		this.interpolationProxies.do({
			arg interpolationProxy;
			interpolationProxy.group.moveBefore(this.group);
		});

		^this;
	}

	//Also moves interpolation proxies
	before {
		arg nextProxy;

		this.group.moveBefore(nextProxy.group);

		this.interpolationProxies.do({
			arg interpolationProxy;
			interpolationProxy.group.moveBefore(this.group);
		});

		^this;
	}

	//Also moves interpolation proxies for next one, used for reverseDo when reordering a block
	beforeMoveNextInterpProxies {
		arg nextProxy;

		this.group.moveBefore(nextProxy.group);

		this.interpolationProxies.do({
			arg interpolationProxy;
			interpolationProxy.group.moveBefore(this.group);
		});

		nextProxy.interpolationProxies.do({
			arg interpolationProxy;
			interpolationProxy.group.moveBefore(nextProxy.group);
		});

		^this;
	}
}

//Alias
ANProxy : AlgaNodeProxy {

}


//Just copied over from Ndef, and ProxySpace replaced with AlgaProxySpace.
//I need to inherit from AlgaNodeProxy though, to make it act the same.
AlgaNdef : AlgaNodeProxy {

	classvar <>defaultServer, <>all;
	var <>key;

	*initClass { all = () }

	*new { | key, object |
		// key may be simply a symbol, or an association of a symbol and a server name
		var res, server, dict;

		if(key.isKindOf(Association)) {
			server = Server.named.at(key.value);
			if(server.isNil) {
				Error("AlgaNdef(%): no server found with this name.".format(key)).throw
			};
			key = key.key;
		} {
			server = defaultServer ? Server.default;
		};

		dict = this.dictFor(server);
		res = dict.envir.at(key);
		if(res.isNil) {
			res = super.new(server).key_(key);
			dict.initProxy(res);
			dict.envir.put(key, res)
		};

		object !? { res.source = object };
		^res;
	}

	*ar { | key, numChannels, offset = 0 |
		^this.new(key).ar(numChannels, offset)
	}

	*kr { | key, numChannels, offset = 0 |
		^this.new(key).kr(numChannels, offset)
	}

	*clear { | fadeTime = 0 |
		all.do(_.clear(fadeTime));
		all.clear;
	}

	*dictFor { | server |
		var dict = all.at(server.name);
		if(dict.isNil) {
			dict = AlgaProxySpace.new(server); // use a proxyspace for ease of access.
			all.put(server.name, dict);
			dict.registerServer;
		};
		^dict
	}

	copy { |toKey|
		if(key == toKey) { Error("cannot copy to identical key").throw };
		^this.class.new(toKey).copyState(this)
	}

	proxyspace {
		^this.class.dictFor(this.server)
	}

	storeOn { | stream |
		this.printOn(stream);
	}
	printOn { | stream |
		var serverString = if (server == Server.default) { "" } {
			" ->" + server.name.asCompileString;
		};
		stream << this.class.name << "(" <<< this.key << serverString << ")"
	}

}

//Alias
ANdef : AlgaNdef {

}