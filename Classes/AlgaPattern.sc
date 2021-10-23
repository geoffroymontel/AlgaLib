// AlgaLib: SuperCollider implementation of the Alga live coding language
// Copyright (C) 2020-2021 Francesco Cameli

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

//This class holds all data for currently active interpSynths / interpBusses and relative
//metadata for an individual AlgaPattern. It's used on the pattern loop to retrieve
//which synths to play, and their current state.
AlgaPatternInterpStreams {
	var <algaPattern;
	var <server;

	//The \dur entry
	var <>dur;

	//Store it for .replace
	var <algaReschedulingEventStreamPlayer;

	//Store it for .replace
	var <>algaSynthBus;

	//Needed to free algaSynthBus / interpSynths / interpBusses on release:
	//there's no other way to know how long a patternSynth would last.
	//Even if the pattern stopped there could be a last tick of 10s length, or whatever.
	//algaPatternSynths will also contain the fxSynths, which can definitely last longer!!!
	var <algaPatternSynths;

	var <entries;
	var <interpSynths;
	var <interpBusses;
	var <interpBussesToFree;

	var <scaleArraysAndChans;
	var <sampleAndHolds;

	*new { | algaPattern |
		^super.new.init(algaPattern)
	}

	init { | argAlgaPattern |
		entries             = IdentityDictionary(10);
		interpSynths        = IdentityDictionary(10);
		interpBusses        = IdentityDictionary(10);
		interpBussesToFree  = IdentitySet();
		algaPatternSynths   = IdentitySet(10);
		scaleArraysAndChans = IdentityDictionary(10);
		sampleAndHolds      = IdentityDictionary(10);
		algaPattern         = argAlgaPattern;
		server              = algaPattern.server;
	}

	//Called from freeAllSynthsOnReplace
	freeAllInterpSynthsAndBusses {
		if(interpSynths != nil, {
			interpSynths.do({ | interpSynthsAtParam |
				interpSynthsAtParam.do({ | interpSynth |
					if(interpSynth != nil, { interpSynth.free })
				});
			});
		});

		if(interpBusses != nil, {
			interpBusses.do({ | interpBussesAtParam |
				interpBussesAtParam.do({ | interpBus |
					if(interpBus != nil, { interpBus.free })
				});
			});
		});
	}

	//Free the algaSynthBus only if all patternSynths are done. This requires .stop to have been
	//first called on the algaReschedulingEventStreamPlayer, otherwise this mechanism will fail!
	freeAllSynthsAndBussesOnReplace {
		AlgaSpinRoutine.waitFor(
			condition: { algaPatternSynths.size == 0 }, //wait for all algaPatternSynths to be done
			func: {
				this.freeAllInterpSynthsAndBusses; //Free all interpSynths / Busses for the stream
				if(algaSynthBus != nil, { algaSynthBus.free }); //Finally, free the algaSynthBus too
			},
			interval: 0.5, //Check every 0.5, not to overload scheduling
			maxTime: nil //maxTime == nil means no maxTime, it will keep going
		)
	}

	//Free all active interpSynths. This triggers the onFree action that's executed in
	//addActiveInterpSynthOnFree
	freeActiveInterpSynthsAtParam { | paramName, time = 0 |
		var interpSynthsAtParam = interpSynths[paramName];
		if(interpSynthsAtParam != nil, {
			interpSynthsAtParam.keysValuesDo({ | uniqueID, interpSynth |
				//Trigger the release of the interpSynth. When freed, the onFree action
				//will be triggered. This is executed thanks to addActiveInterpSynthOnFree.
				//Note that only the first call to \t_release will be used as trigger, while
				//\fadeTime will always be set on any consecutive call,
				//even after the first trigger of \t_release.
				interpSynth.set(
					\t_release, 1,
					\fadeTime, time,
				);
			});
		});
	}

	//This creates a temporary patternSynth for mid-pattern interpolation.
	//It will be freed at the start of the NEXT paramSynth.
	createTemporaryPatternParamSynthAtParam { | entry, uniqueID, paramName, paramNumChannels,
		paramRate, paramDefault, time = 0 |

		var clock = algaPattern.clock;

		//It's essential that this is scheduled at the bottom of the Clock.
		//This allows this action to always be executed AFTER the pattern triggers.
		clock.algaSchedAtQuantOnce(
			quant: 0, //Execute right now at the bottom of the TempoClock stack (after all eventual pattern triggers)
			task: {
				//The scale and chans of the interpStream
				var scaleArraysAndChansAtParam = scaleArraysAndChans[paramName];

				//These belong to the latest patternSynth created. Since the action was executed with
				//top priority, the latest bus will be the one created by the latest patternSynth.
				//This will then be used to write to if scheduling happens mid-pattern.
				var latestPatternInterpSumBusAtParam = algaPattern.latestPatternInterpSumBusses[paramName];
				var latestPatternTime = algaPattern.latestPatternTime;

				//These belong to all active patternSynths
				var patternBussesAndSynths = algaPattern.currentPatternBussesAndSynths[latestPatternInterpSumBusAtParam];
				var activePatternInterpSumBusses;

				//FUNDAMENTAL:
				//Only schedule if the same pattern HAS NOT been triggered at this very time.
				//This solves all scheduling issues, and allows schedule times that are not in sync to work
				if(latestPatternTime != clock.seconds, {
					if((latestPatternInterpSumBusAtParam != nil).and(patternBussesAndSynths != nil), {
						//FUNDAMENTAL: check that the bus is still actually valid and hasn't been freed yet.
						//In case it's been freed, it means the synths have already been freed
						if(latestPatternInterpSumBusAtParam.bus != nil, {
							algaPattern.createPatternParamSynth(
								entry: entry,
								uniqueID: uniqueID,
								paramName: paramName,
								paramNumChannels: paramNumChannels,
								paramRate: paramRate,
								paramDefault: paramDefault,
								patternInterpSumBus: latestPatternInterpSumBusAtParam,
								patternBussesAndSynths: patternBussesAndSynths,
								scaleArraysAndChansAtParam: scaleArraysAndChansAtParam,
								sampleAndHold: false,
								algaPatternInterpStreams: this,
								isTemporary: true
							)
						});
					});
				});

				//Retrieve active ones (including the newly created one)
				activePatternInterpSumBusses = algaPattern.currentActivePatternInterpSumBusses;

				//Add temporary patterns for all active patternInterpBusses at paramName
				if(activePatternInterpSumBusses != nil, {
					var activePatternInterpSumBussesAtParam = activePatternInterpSumBusses[paramName];
					if(activePatternInterpSumBussesAtParam != nil, {
						activePatternInterpSumBussesAtParam.do({ | patternInterpBusAtParam |
							//Don't do it for the patternInterpBusAtParam that's just been created!
							if(patternInterpBusAtParam != latestPatternInterpSumBusAtParam, {
								if(patternInterpBusAtParam.bus != nil, {
									//Simply "stop" the envelopes of all active patternSynths.
									//This allows them to be kept alive even though
									//the interpSynth might have been freed meanwhile. This will "freeze" the
									//interpolation of running patternSynths.
									var currentActivePatternSynthsAtInterpBus =
									algaPattern.currentActivePatternParamSynths[patternInterpBusAtParam];
									if(currentActivePatternSynthsAtInterpBus != nil, {
										currentActivePatternSynthsAtInterpBus.do({ | patternParamSynth |
											patternParamSynth.set(\sampleAndHold, 1, \t_sah, 1);
										});
									});
								});
							});
						});
					});
				});
			}
		);
	}

	//Each param has its own interpSynth and bus. These differ from AlgaNode's ones,
	//as they are not embedded with the interpolation behaviour itself, but they are external.
	//This allows to separate the per-tick pattern triggering from the interpolation process.
	createPatternInterpSynthAndBusAtParam { | paramName, paramRate, paramNumChannels,
		entry, uniqueID, time = 0 |

		var interpGroup = algaPattern.interpGroup;
		var interpBus, interpSynth;
		var scaleArray;

		//Holds no paramNumChannels infos
		var interpSymbol = (
			"alga_pattern_interp_env_" ++
			paramRate
		).asSymbol;

		//Retrieve all active interpSynths at the current param.
		var interpSynthsAtParam = interpSynths[paramName];

		//alga_pattern_interp_env_... outputs one channel only
		interpBus = AlgaBus(server, 1, paramRate);

		//Each param / entry combination has its own interpSynth and interpBus!
		//This behaviour is different from AlgaNode, which dynamically replaces the previous one.
		//However, pattern synths are created on the fly, so the interpSynths need to be kept alive until
		//interpolation has finished. In a nutshell, patternSynths and interpSynths are decoupled.
		if(interpSynthsAtParam == nil, {
			//If it's the first synth, fadeTime is 0.
			//This only happens when first creating the AlgaPattern!
			interpSynth = AlgaSynth(
				interpSymbol,
				[\out, interpBus.index, \fadeTime, 0],
				interpGroup
			);
			interpSynths[paramName] = IdentityDictionary().put(uniqueID, interpSynth);
			interpBusses[paramName] = IdentityDictionary().put(uniqueID, interpBus);
		}, {
			//If it's not the first synth, fadeTime is time
			interpSynth = AlgaSynth(
				interpSymbol,
				[\out, interpBus.index, \fadeTime, time],
				interpGroup
			);
			interpSynths[paramName].put(uniqueID, interpSynth);
			interpBusses[paramName].put(uniqueID, interpBus);
		});

		//Add entries to algaPattern too.These are needed for algaInstantiatedAsReceiver.
		//Note: no mixing yet
		algaPattern.interpSynths[paramName][\default] = interpSynth;
		algaPattern.interpBusses[paramName][\default] = interpBus;

		//Add interpSynth to the current active ones for specific param / sender combination.
		//Also add a "onFree" routine that deletes unused entries from Dictionaries. This function
		//is called on freeing the interpSynth.
		//Note: no mixing yet
		algaPattern.addActiveInterpSynthOnFree(paramName, \default, interpSynth, {
			var entriesAtParam              = entries[paramName];
			var interpSynthsAtParam         = interpSynths[paramName];
			var interpBussesAtParam         = interpBusses[paramName];
			var scaleArraysAndChansAtParam  = scaleArraysAndChans[paramName];
			var sampleAndHoldAtParam        = sampleAndHolds[paramName];

			//Remove entry from entries and deal with inNodes / outNodes for both receiver and sender
			if(entriesAtParam != nil, {
				var entryAtParam = entriesAtParam[uniqueID];
				entriesAtParam.removeAt(uniqueID);
			});

			//Remove scaleArray and chans
			if(scaleArraysAndChansAtParam != nil, {
				var scaleArrayAndChansAtParam = scaleArraysAndChansAtParam[uniqueID];
				scaleArraysAndChansAtParam.removeAt(uniqueID);
				if(scaleArrayAndChansAtParam != nil, {
					algaPattern.removeScaling(paramName, uniqueID);
				});
			});

			//Remove sampleAndHold
			if(sampleAndHoldAtParam != nil, { sampleAndHolds.removeAt(paramName) });

			//Remove entry from interpSynths
			if(interpSynthsAtParam != nil, { interpSynthsAtParam.removeAt(uniqueID) });

			//Remove entry from interpBusses and add entry to interpBussesToFree.
			//IMPORTANT: interpBusAtParam CAN'T be freed here, as dangling synths can still
			//be executed, and they would be (wrongly) pointing to the interpBus, causing
			//wrong results at the end of the interpolation process. interpBusAtParam needs
			//to be freed in the pattern loop.
			if(interpBussesAtParam != nil, {
				var interpBusAtParam = interpBussesAtParam[uniqueID];
				interpBussesAtParam.removeAt(uniqueID);
				//interpBussesToFree are freed in the pattern loop when patternSynth gets freed.
				//This is essential for the interpolation process to correctly only free busses
				//when completely unused on both lang and server!
				//interpBusAtParam can't be freed here as it can still be used if the patternSynth
				//takes longer to free itself (perhaps a long envelope) than the interpolation synth.
				if(interpBusAtParam != nil, { interpBussesToFree.add(interpBusAtParam) });
			});
		});
	}

	//Wrapper around AlgaNode's addInOutNodesDict.
	//If entry is a ListPattern, loop around it and add each entry that is an AlgaNode.
	addInOutNodesDictAtParam { | sender, param, mix = false |
		algaPattern.addInOutNodesDict(sender, param, mix)
	}

	//Wrapper around AlgaNode's removeInOutNodesDict.
	//If entry is a ListPattern, loop around it and remove each entry that is an AlgaNode.
	removeAllInOutNodesDictAtParam { | paramName |
		algaPattern.removeInOutNodesDict(nil, paramName) //nil removes them all
	}

	//add a scaleArray and chans
	addScaleArrayAndChans { | paramName, paramNumChannels, uniqueID, chans, scale |
		var scaleArraysAndChansAtParam = scaleArraysAndChans[paramName];

		//Pattern support
		chans = chans.algaAsStream;
		scale = scale.algaAsStream;

		if(scaleArraysAndChansAtParam == nil, {
			scaleArraysAndChans[paramName] = IdentityDictionary().put(uniqueID, [scale, chans]);
		}, {
			scaleArraysAndChans[paramName].put(uniqueID, [scale, chans]);
		});
	}

	//Add entry / entryOriginal to dictionaries
	addEntry { | entry, paramName, uniqueID |
		var  entriesAtParam = entries[paramName];
		//Either create a new Dict for the param, or add to existing one
		if(entriesAtParam == nil, {
			entries[paramName] = IdentityDictionary().put(uniqueID, entry);
			^true; //first entry
		}, {
			entries[paramName].put(uniqueID, entry);
			^false; //not first entry
		});
		^false;
	}

	//Add sampleAndHold at param. It's generic for that param!
	addSampleAndHold { | paramName, sampleAndHold |
		sampleAndHolds[paramName] = sampleAndHold
	}

	//Add a new sender interpolation to the current param
	add { | entry, controlName, chans, scale, sampleAndHold, time = 0 |
		var paramName, paramRate, paramNumChannels, paramDefault;
		var uniqueID;

		var entryOriginal = entry; //Original entry, not as Stream. Needed for addInOutNodesDictAtParam
		var isFirstEntry;

		if(controlName == nil, {
			("AlgaPatternInterpStreams: Invalid controlName for param '" ++ paramName ++ "'").error
		});

		//Unpack
		paramName = controlName.name;
		paramRate = controlName.rate;
		paramNumChannels = controlName.numChannels;
		paramDefault = controlName.defaultValue;

		//If entry is nil, use paramDefault (used for .reset)
		if(entry == nil, { entry = paramDefault; entryOriginal = paramDefault });

		//Interpret entry as Stream
		entry = entry.algaAsStream;

		//Use an unique id as index as it's more reliable than using the entry as key:
		//entry could very well be a number (like 440), screwing things up in IdentityDict.
		uniqueID = UniqueID.next;

		//Add entry to dict
		isFirstEntry = this.addEntry(entry, paramName, uniqueID);

		//Add the scaleArray and chans
		this.addScaleArrayAndChans(paramName, paramNumChannels, uniqueID, chans, scale);

		//Add sampleAndHold
		this.addSampleAndHold(paramName, sampleAndHold);

		//Remove all older inNodes / outNodes... Doesn't work with mix yet
		this.removeAllInOutNodesDictAtParam(paramName);

		//Add proper inNodes / outNodes / connectionTimeOutNodes. Use entryOriginal in order
		//to retrieve if it is a ListPattern.
		this.addInOutNodesDictAtParam(entryOriginal, paramName, false);

		//Trigger the interpolation process on all the other active interpSynths.
		//This must always be before createPatternInterpSynthAndBusAtParam
		this.freeActiveInterpSynthsAtParam(
			paramName,
			time
		);

		//Create the interpSynth and interpBus for the new sender
		this.createPatternInterpSynthAndBusAtParam(
			paramName: paramName,
			paramRate: paramRate,
			paramNumChannels: paramNumChannels,
			entry: entry,
			uniqueID: uniqueID,
			time: time
		);

		//Create a temporary pattern param synth to start the interpolation process with.
		//Only one per-change is needed, as the interpolation process will continue as soon as the pattern
		//triggers new synth. This just avoids to have silences and gaps when modifying a param mid-pattern.
		//This must come AFTER createPatternInterpSynthAndBusAtParam.
		if((isFirstEntry.not).and(sampleAndHold.not), {
			//NOTE: this doesn't support MC yet
			this.createTemporaryPatternParamSynthAtParam(
				entry: entry,
				uniqueID: uniqueID,
				paramName: paramName,
				paramNumChannels: paramNumChannels,
				paramRate: paramRate,
				paramDefault: paramDefault,
				time: time
			);
		});
	}

	//Play a pattern as an AlgaReschedulingEventStreamPlayer and return it
	playAlgaReschedulingEventStreamPlayer { | pattern, clock |
		algaReschedulingEventStreamPlayer = pattern.playAlgaRescheduling(clock:clock)
	}
}

//This class is used to specify individual parameters of a pattern argument.
//It can be used to dynamically set parameters of a connected Node (like scale and chans).
AlgaArg {
	var <sender, <chans, <scale;

	*new { | node, chans, scale |
		^super.new.init(node, chans, scale)
	}

	init { | argSender, argChans, argScale |
		sender = argSender.algaAsStream; //Pattern support
		chans  = argChans.algaAsStream;  //Pattern support
		scale  = argScale.algaAsStream;  //Pattern support
	}

	isAlgaArg { ^true }

	algaInstantiatedAsSender {
		if(sender.isAlgaNode, { ^sender.algaInstantiatedAsSender });
		^false
	}

	//Used in AlgaBlock
	blockIndex {
		if(sender.isAlgaNode, { ^sender.blockIndex });
		^(-1);
	}

	//Used in AlgaBlock
	blockIndex_ { | val |
		if(sender.isAlgaNode, { sender.blockIndex_(val) });
	}

	//Used in AlgaBlock
	inNodes {
		if(sender.isAlgaNode, { ^sender.inNodes });
		^nil
	}

	//Used in AlgaBlock
	outNodes {
		if(sender.isAlgaNode, { ^sender.outNodes });
		^nil
	}
}

//This class is used for the \out parameter... Should it also store time?
//Perhaps, the first node -> time pair should be considered if using a ListPattern:
/*
out: (
Pseq([
AlgaOut(a, \freq, scale:[20, 30], time:2),   <-- uses time: 2 also for the later 'a'
AlgaOut(b, \freq, scale:[10, 50], time:3),   <-- uses time: 3 also for the later 'b'
b,
a
], inf)
)
*/
AlgaOut {
	var <node, <param, <chans, <scale;

	*new { | node, param, chans, scale |
		^super.new.init(node, param, chans, scale)
	}

	init { | argNode, argParam, argChans, argScale |
		node   = argNode.algaAsStream;  //Pattern support
		param  = argParam.algaAsStream; //Pattern support
		chans  = argChans.algaAsStream; //Pattern support
		scale  = argScale.algaAsStream; //Pattern support
	}

	isAlgaOut { ^true }
}

//This class is used to create a temporary AlgaNode for a parameter in an AlgaPattern
AlgaTemp {
	var <def, <chans, <scale;
	var <controlNames;
	var <numChannels, <rate;
	var <valid = false;

	*new { | def, chans, scale |
		^super.new.init(def, chans, scale)
	}

	init { | argDef, argChans, argScale |
		def    = argDef;
		chans  = argChans.algaAsStream;  //Pattern support
		scale  = argScale.algaAsStream;  //Pattern support
	}

	setDef { | argDef |
		def = argDef
	}

	checkValidSynthDef { | def |
		var synthDesc = SynthDescLib.global.at(def);
		var synthDef;

		if(synthDesc == nil, {
			("AlgaTemp: Invalid AlgaSynthDef: '" ++ def.asString ++ "'").error;
			^nil;
		});

		synthDef = synthDesc.def;

		if(synthDef.class != AlgaSynthDef, {
			("AlgaTemp: Invalid AlgaSynthDef: '" ++ def.asString ++"'").error;
			^nil;
		});

		numChannels = synthDef.numChannels;
		rate = synthDef.rate;
		controlNames = synthDesc.controls;

		//Set validity
		if((numChannels != nil).and(rate != nil).and(controlNames != nil), { valid = true });
	}

	isAlgaTemp { ^true }
}

//AlgaPattern
AlgaPattern : AlgaNode {
	/*
	TODOs:

	1) mixFrom()
	2) \dur interpolation
	*/

	//The actual Patterns to be manipulated
	var <pattern;

	//The pattern as stream
	var <patternAsStream;

	//The Event input
	var <eventPairs;

	//Use MC expansion or not
	var <useMultiChannelExpansion = true;

	//interpStreams. These varies on .replace
	var <interpStreams;

	//numChannels when using a ListPattern as \def
	var <numChannelsList;

	//rate when using a ListPattern as \def
	var <rateList;

	//controlNames when using a ListPattern as \def
	var <controlNamesList;

	//Set \dur interpolation behaviour. Either run .replace or change at sched.
	var <replaceDur = false;

	//Keep track of the CURRENT patternInterpSumBus AlgaBus per-param. This is updated every pattern trigger.
	//It is fundamental to have the current one in order to add do it a mid-pattern synth when user
	//changes a param mid-pattern.
	var <>latestPatternInterpSumBusses;

	//Keep track of latest clock time
	var <latestPatternTime;

	//Keep track of ALL active patternParamSynths
	var <>currentActivePatternParamSynths;

	//Keep track of ALL active interpBusses
	var <>currentActiveInterpBusses;

	//Keep track of ALL active patternInterpSumBusses
	var <>currentActivePatternInterpSumBusses;

	//Keep track of ALL active patternBussesAndSynths
	var <>currentPatternBussesAndSynths;

	//Current fx
	var <currentFX;

	//Current \out
	var <currentOut;

	//Current nodes of used in currentOut
	var <currentPatternOutNodes;

	//Current time used for \out replacement
	var <currentPatternOutTime;

	//Needed to store reset for various alga params (\out, \fx, etc...)
	var <currentReset;

	//Needed to store current generic params
	var <currentGenericParams;

	//Skip an iteration
	var skipIteration = false;

	//Skip an iteration for FX
	var skipIterationFX = false;

	//Add the \algaNote event to Event
	*initClass {
		//StartUp.add is needed: Event class must be compiled first
		StartUp.add({ this.addAlgaNoteEventType });
	}

	//Doesn't have args and outsMapping like AlgaNode. Default sched to 1 (so it plays on clock)
	*new { | def, interpTime, playTime, sched = 1, server |
		^super.new(
			def: def,
			interpTime: interpTime,
			playTime: playTime,
			server: server,
			sched: sched
		);
	}

	//Add the \algaNote event type
	*addAlgaNoteEventType {
		Event.addEventType(\algaNote, #{
			//The final OSC bundle
			var bundle;

			//The AlgaSynthDef
			var algaSynthDef = ~synthDefName.valueEnvir;

			//AlgaPattern, the synthBus and its server / clock
			var algaPattern = ~algaPattern;
			var algaSynthBus = ~algaSynthBus;
			var algaPatternServer = ~algaPatternServer;
			var algaPatternClock = ~algaPatternClock;

			//The interpStreams the Pattern is using
			var algaPatternInterpStreams = ~algaPatternInterpStreams;

			//fx
			var fx = ~fx;

			//out
			var algaOut = ~algaOut;

			//Other things for pattern syncing / clocking / scheduling
			var offset = ~timingOffset;
			var lag = ~lag;

			//Needed ?
			~isPlaying = true;

			//Create the bundle with all needed Synths for this Event.
			bundle = algaPatternServer.makeBundle(false, {
				~algaPattern.createEventSynths(
					algaSynthDef: algaSynthDef,
					algaSynthBus: algaSynthBus,
					algaPatternInterpStreams: algaPatternInterpStreams,
					fx: fx,
					algaOut: algaOut
				)
			});

			//Send bundle to server using the same server / clock as the AlgaPattern
			//Note that this does not go through the AlgaScheduler directly, but it uses its same clock!
			schedBundleArrayOnClock(
				offset,
				algaPatternClock,
				bundle,
				lag,
				algaPatternServer
			);
		});
	}

	//Set dur asStream for it to work within Pfuncn
	setDur { | value, newInterpStreams |
		if(newInterpStreams == nil, {
			interpStreams.dur = value.algaAsStream
		}, {
			newInterpStreams.dur = value.algaAsStream
		});
	}

	//Set replaceDur
	replaceDur_ { | value = false |
		if((value != false).and(value != true), {
			"AlgaPattern: 'replaceDur' only supports boolean values. Setting it to false".error;
			value = false;
		});
		replaceDur = value
	}

	//Set useMultiChannelExpansion
	useMultiChannelExpansion_ { | value = false |
		if((value != false).and(value != true), {
			"AlgaPattern: 'useMultiChannelExpansion' only supports boolean values. Setting it to false".error;
			value = false;
		});
		useMultiChannelExpansion = value
	}

	//Alias
	multiChannelExpansion {
		^useMultiChannelExpansion
	}

	//Alias
	multiChannelExpansion_ { | value = false |
		this.useMultiChannelExpansion(value)
	}

	//Free all unused busses from interpStreams
	freeUnusedInterpBusses { | algaPatternInterpStreams |
		var interpBussesToFree = algaPatternInterpStreams.interpBussesToFree;
		interpBussesToFree.do({ | interpBus |
			//If the identity set is empty, it needs to be freed
			var toBeFreed = currentActiveInterpBusses[interpBus].size == 0;
			if(toBeFreed, {
				interpBus.free;
				interpBussesToFree.remove(interpBus);
				currentActiveInterpBusses.removeAt(interpBus);
			});
		});
	}

	//Create a temporary synth according to the specs of the AlgaTemp
	createAlgaTempSynth { | algaTemp, patternBussesAndSynths |
		var tempBus, tempSynth;
		var tempSynthArgs = Array.newClear;
		var tempNumChannels = algaTemp.numChannels;
		var tempRate = algaTemp.rate;
		var def, algaTempDef, controlNames;
		var defIsEvent = false;

		//Check AlgaTemp validity
		if(algaTemp.valid.not, {
			"AlgaPattern: Invalid AlgaTemp, using default parameter".error;
			^nil
		});

		//Unpack SynthDef
		algaTempDef = algaTemp.def;
		def = algaTempDef;

		//If Event, SynthDef is under [\def]
		if(algaTempDef.isEvent, {
			def = algaTempDef[\def];
			defIsEvent = true;
		});

		//Unpack controlNames
		controlNames = algaTemp.controlNames;

		//Loop around the controlNames to set relevant parameters
		controlNames.do({ | controlName |
			var paramName = controlName.name;
			var paramNumChannels = controlName.numChannels;
			var paramRate = controlName.rate;
			var paramDefault = controlName.defaultValue;
			var entry;

			//Retrieve param if entry is Event
			if(defIsEvent, { entry = algaTempDef[paramName] });

			//If entry is nil, the tempSynth will already use the default value
			if(entry != nil, {
				//Ignore static params
				if((paramName != '?').and(paramName != \instrument).and(
					paramName != \def).and(paramName != \out).and(
					paramName != \gate).and(paramName != \fadeTime), {

					//Temporary bus that the patternParamSynth for the fx will write to
					var patternParamBus = AlgaBus(server, paramNumChannels, paramRate);

					//Add bus to tempSynth at correct paramName
					tempSynthArgs = tempSynthArgs.add(paramName).add(patternParamBus.busArg);

					//Register bus to be freed
					patternBussesAndSynths.add(patternParamBus);

					//Create a patternParamSynth for the temp param
					this.createPatternParamSynth(
						entry: entry,
						uniqueID: nil,
						paramName: paramName,
						paramNumChannels: paramNumChannels,
						paramRate: paramRate,
						paramDefault: paramDefault,
						patternInterpSumBus: patternParamBus,
						patternBussesAndSynths: patternBussesAndSynths,
						isFX: true //use isFX (it's the same behaviour)
					)
				});
			});
		});

		//The AlgaBus the tempSynth will write to
		tempBus = AlgaBus(server, tempNumChannels, tempRate);

		//Write output to tempBus
		tempSynthArgs = tempSynthArgs.add(\out).add(tempBus.index);

		//The actual AlgaSynth
		tempSynth = AlgaSynth(
			def,
			tempSynthArgs,
			interpGroup,
			\addToTail,
			false
		);

		//Add Bus and Synth to patternBussesAndSynths
		patternBussesAndSynths.add(tempBus);
		patternBussesAndSynths.add(tempSynth);

		//Return the AlgaBus that the tempSynth writes to
		^tempBus.busArg;
	}

	//Add patternParamSynth as child of patternInterpSumBus (used to create temporary synths)
	addActivePatternParamSynth { | patternInterpSumBus, patternParamSynth |
		//Add to currentActivePatternParamSynths
		currentActivePatternParamSynths[patternInterpSumBus] = currentActivePatternParamSynths[patternInterpSumBus] ? IdentitySet();
		currentActivePatternParamSynths[patternInterpSumBus].add(patternParamSynth);
	}

	//Add patternParamSynth as child of a specific patternEnvBus
	addActiveInterpBus { | patternParamEnvBus, patternParamSynth |
		//Add current active currentActiveInterpBusses
		currentActiveInterpBusses[patternParamEnvBus] = currentActiveInterpBusses[patternParamEnvBus] ? IdentitySet();
		currentActiveInterpBusses[patternParamEnvBus].add(patternParamSynth);

		//Remove the entry on patternParamSynth's free
		patternParamSynth.onFree({
			var set = currentActiveInterpBusses[patternParamEnvBus];
			if(set != nil, { set.remove(patternParamSynth) });
		});
	}

	//Create one pattern synth with the entry / uniqueID pair at paramName
	//This is the core of the interpolation behaviour for AlgaPattern !!
	createPatternParamSynth { | entry, uniqueID, paramName, paramNumChannels, paramRate,
		paramDefault, patternInterpSumBus, patternBussesAndSynths, scaleArraysAndChansAtParam,
		sampleAndHold, algaPatternInterpStreams, isFX = false, isTemporary = false |

		var sender, senderNumChannels, senderRate;
		var chansMapping, scale;
		var validParam = false;
		var isAlgaTempInFX = false;

		//Unpack Pattern value
		//(Only if not using MC, or isFX (no MC in FX) or isTemporary (no MC in temporary))
		if((useMultiChannelExpansion.not).or(isFX).or(isTemporary), {
			if(entry.isStream, { entry = entry.next });
		});

		//Check if it's an AlgaArg. Unpack it.
		if(entry.isAlgaArg, {
			chansMapping = entry.chans;
			scale        = entry.scale;
			entry        = entry.sender;
			//Unpack Pattern value
			if(entry.isStream, { entry = entry.next });
		});

		//Check if it's an AlgaTemp. Create it
		if(entry.isAlgaTemp, {
			var algaTemp = entry;

			//If valid, this returns the AlgaBus that the tempSynth will write to
			entry = this.createAlgaTempSynth(
				algaTemp: algaTemp,
				patternBussesAndSynths: patternBussesAndSynths
			);

			if(entry.isNil.not, {
				//Valid AlgaTemp. entry is an AlgaBus now
				chansMapping      = algaTemp.chans;
				scale             = algaTemp.scale;
				senderRate        = algaTemp.rate;
				senderNumChannels = algaTemp.numChannels;
				validParam        = true;
				if(isFX, { isAlgaTempInFX = true });
			}, {
				//entry == nil
				senderRate = "control";
				senderNumChannels = paramNumChannels;
				entry = paramDefault;
				validParam = true;
			});
		});

		//Fallback sender (modified for AlgaNode, needed for chansMapping)
		sender = entry;

		//Valid values are Numbers / Arrays / AlgaNodes / Buffers / Nils
		case

		//Number / Array
		{ entry.isNumberOrArray } {
			if(entry.isArray, {
				//an array
				senderNumChannels = entry.size;
				senderRate = "control";
			}, {
				//a num
				senderNumChannels = 1;
				senderRate = "control";
			});
			validParam = true;
		}

		//AlgaNode
		{ entry.isAlgaNode } {
			sender = entry; //essential for chansMapping (entry gets modified)
			if(entry.algaInstantiated, {
				if((entry.algaCleared).or(entry.algaToBeCleared), {
					//("AlgaPattern: can't connect to an AlgaNode that's been cleared").error;
					^this;
				}, {
					if(entry.synthBus != nil, {
						if(entry.synthBus.bus != nil, {
							senderRate = entry.rate;
							senderNumChannels = entry.numChannels;
							entry = entry.synthBus.busArg;
							validParam = true;
						}, {
							("AlgaPattern: can't connect to an AlgaNode with an invalid synthBus").error;
						});
					}, {
						("AlgaPattern: can't connect to an AlgaNode with an invalid synthBus").error;
					});
				});
			}, {
				//otherwise, use default
				senderRate = "control";
				senderNumChannels = paramNumChannels;
				entry = paramDefault;
				("AlgaPattern: AlgaNode wasn't algaInstantiated yet. Using default value for " ++ paramName).warn;
				validParam = true;
			});
		}

		//Buffer
		{ entry.isBuffer } {
			entry = entry.bufnum;
			senderNumChannels = 1;
			senderRate = "control";
			validParam = true;
		}

		//Nil, use default
		{ entry.isNil } {
			senderRate = "control";
			senderNumChannels = paramNumChannels;
			entry = paramDefault;
			("AlgaPattern: trying to set 'nil' for param '" ++ paramName ++
				"'. Using default value " ++ paramDefault.asString ++" instead").error;
			validParam = true;
		}

		//Symbol (like, \skip or \rest): skip iteration.
		//It's very important not to use Rest() here, as \dur will also pick it up, generating
		//an actual double Rest(). Rest() should only be used in \dur / \delta.
		{ entry.isSymbol } {
			if(isFX.not, { skipIteration = true }, { skipIterationFX = true });
			^this;
		};

		if(validParam, {
			//Get the bus where interpolation envelope is written to...
			//REMEMBER that for AlgaPattern, interpSynths are actually JUST the
			//interpolation envelope, which is then passed through this individual synths!
			// ... Now, I need to keep track of all the active interpBusses instead, not retrievin
			//from interpBusses, which gets replaced in language, but should implement the same
			//behaviour of activeInterpSynths and get busses from there.
			var patternParamEnvBus;
			var validPatternParamEnvBus = true;

			if(isFX.not, {
				//Not \fx parameter: retrieve correct envelope bus
				patternParamEnvBus = algaPatternInterpStreams.interpBusses[paramName][uniqueID];
				validPatternParamEnvBus = patternParamEnvBus != nil;
				if(validPatternParamEnvBus, { validPatternParamEnvBus = patternParamEnvBus.bus != nil });
			});

			if(validPatternParamEnvBus, {
				var patternParamSynth;
				var patternParamSymbol;
				var patternParamSynthArgs;
				var scaleArrayAndChansAtParam;
				var scaleArray;
				var indices;

				if(isFX.not, {
					//Args for patternParamSynth
					patternParamSynthArgs = [
						\in, entry,
						\env, patternParamEnvBus.busArg,
						\out, patternInterpSumBus.index,
						\fadeTime, 0
					];

					//get correct scaleArray and chans
					scaleArrayAndChansAtParam = scaleArraysAndChansAtParam[uniqueID];

					//If AlgaPatternArg's is nil, use argument's one (if defined)
					if(scale == nil, {
						if(scaleArrayAndChansAtParam != nil, { scale = scaleArrayAndChansAtParam[0] }); //0 == scaleArray
					});
				}, {
					//FX has no env
					patternParamSynthArgs = [
						\in, entry,
						\out, patternInterpSumBus.index,
						\fadeTime, 0
					];
				});

				//Calculate scaleArray
				scaleArray = this.calculateScaling(
					paramName,
					sender,
					paramNumChannels,
					scale,
					addScaling: false, //don't update the AlgaNode's scalings dict
				);


				if(scaleArray != nil, {
					patternParamSynthArgs = patternParamSynthArgs.addAll(scaleArray);
				});

				//\fx parameter does not use global scaleArrayAndChans
				if(isFX.not, {
					//If AlgaPatternArg's is nil, use argument's one (if defined)
					if(chansMapping == nil, {
						if(scaleArrayAndChansAtParam != nil, { chansMapping = scaleArrayAndChansAtParam[1] }); //1 == chans
					});
				});

				//Always calculate chansMapping for the modulo around paramNumChannels!
				indices = this.calculateSenderChansMappingArray(
					paramName,
					sender,
					chansMapping,
					senderNumChannels,
					paramNumChannels,
					updateParamsChansMapping: false, //don't update the AlgaNode's chans dict
				);

				//Add \indices (chans)
				patternParamSynthArgs = patternParamSynthArgs.add(\indices).add(indices);

				//sampleAndHold (use == true) as sampleAndHold could be nil too
				if(sampleAndHold == true, {
					patternParamSynthArgs = patternParamSynthArgs.add(\sampleAndHold).add(1).add(\t_sah).add(1);
				});

				if(isFX.not, {
					//Standard case
					patternParamSymbol = (
						"alga_pattern_" ++
						senderRate ++
						senderNumChannels ++
						"_" ++
						paramRate ++
						paramNumChannels
					).asSymbol;
				}, {
					//\fx parameter case
					patternParamSymbol = (
						"alga_pattern_" ++
						senderRate ++
						senderNumChannels ++
						"_" ++
						paramRate ++
						paramNumChannels ++
						"_fx"
					).asSymbol;
				});

				//Actual synth for the param
				patternParamSynth = AlgaSynth(
					patternParamSymbol,
					patternParamSynthArgs,
					interpGroup,
					\addToTail,
					waitForInst: false
				);

				//Register patternParamSynth to be freed
				patternBussesAndSynths.add(patternParamSynth);

				//Don't add the FX patternParamSynths: they're already handled
				if(isFX.not,{
					//Add patternParamSynth as child of patternInterpSumBus (used to create temporary synths)
					this.addActivePatternParamSynth(patternInterpSumBus, patternParamSynth);

					//Add patternParamSynth as child of patternEnvBus (used to free patternEnvBus when appropriate)
					this.addActiveInterpBus(patternParamEnvBus, patternParamSynth);
				});
			});
		}, {
			("AlgaPattern: Invalid class '" ++ entry.class ++ "' for parameter '" ++ paramName.asString ++ "'").error;
		});
	}

	//Create all pattern synths per-param
	createPatternParamSynths { | paramName, paramNumChannels, paramRate,
		paramDefault, patternInterpSumBus, patternBussesAndSynths,
		interpStreamsEntriesAtParam, scaleArraysAndChansAtParam, sampleAndHold,
		algaPatternInterpStreams, fx, mcSynthNum, mcEntriesAtParam |

		//If MC, loop from mcEntriesAtParam
		if(useMultiChannelExpansion, {
			//MC: loop from mcEntriesAtParam
			if(mcEntriesAtParam != nil, {
				mcEntriesAtParam.keysValuesDo({ | uniqueID, entry | //indexed by uniqueIDs
					if(mcSynthNum != nil, { entry = entry[mcSynthNum] }); //extract from MC array
					this.createPatternParamSynth(
						entry: entry,
						uniqueID: uniqueID,
						paramName: paramName,
						paramNumChannels: paramNumChannels,
						paramRate: paramRate,
						paramDefault: paramDefault,
						patternInterpSumBus: patternInterpSumBus,
						patternBussesAndSynths: patternBussesAndSynths,
						scaleArraysAndChansAtParam: scaleArraysAndChansAtParam,
						sampleAndHold: sampleAndHold,
						algaPatternInterpStreams: algaPatternInterpStreams
					)
				});
			});
		}, {
			//No MC: loop from interpStreamsEntriesAtParam
			if(interpStreamsEntriesAtParam != nil, {
				interpStreamsEntriesAtParam.keysValuesDo({ | uniqueID, entry | //indexed by uniqueIDs
					this.createPatternParamSynth(
						entry: entry,
						uniqueID: uniqueID,
						paramName: paramName,
						paramNumChannels: paramNumChannels,
						paramRate: paramRate,
						paramDefault: paramDefault,
						patternInterpSumBus: patternInterpSumBus,
						patternBussesAndSynths: patternBussesAndSynths,
						scaleArraysAndChansAtParam: scaleArraysAndChansAtParam,
						sampleAndHold: sampleAndHold,
						algaPatternInterpStreams: algaPatternInterpStreams
					)
				});
			});
		});
	}

	//Create all needed Synths and Busses for an FX
	createFXSynthAndPatternSynths { | fx, numChannelsToUse, rateToUse,
		algaSynthDef, algaSynthBus, algaPatternInterpStreams,
		patternSynthArgs, patternBussesAndSynths |

		var patternBussesAndSynthsFx = IdentitySet();
		var def = fx[\def];
		var explicitFree = fx[\explicitFree];
		var controlNamesAtFX = fx[\controlNames];

		//NumChannels / rate of the \in param of fx
		var fxInNumChannels = fx[\inNumChannels];
		var fxInRate = fx[\inRate];

		//NumChannels / rate of the fx
		var fxNumChannels = fx[\numChannels];
		var fxRate = fx[\rate];

		//Args to fxSynth
		var fxSynthArgs = Array.newClear;

		//Actual fxSynth
		var fxSynthSymbol;
		var fxSynth;

		//If channels mismatch, these will be used to create final conversion
		//from fxSynth to algaSynthBus.
		var fxInterpSynthSymbol, fxInterpSynthArgs, fxInterpSynth;

		//Create the Bus patternSynth will write to.
		//It uses numChannelsToUse and rateToUse, which are set accordingly to the \def specs
		var patternBus = AlgaBus(server, numChannelsToUse, rateToUse);

		//The final free func
		var onFXSynthFreeFunc;

		//Add patternBus to patternBussesAndSynthsFx
		patternBussesAndSynthsFx.add(patternBus);

		//Unpack parameters (same behaviour as createPatternParamSynth's unpacking)
		controlNamesAtFX.do({ | controlName |
			var paramName = controlName.name;
			var paramNumChannels = controlName.numChannels;
			var paramRate = controlName.rate;
			var paramDefault = controlName.defaultValue;
			var entry = fx[paramName];

			//If entry is nil, the tempSynth will already use the default value
			if(entry != nil, {
				//Ignore static params
				if((paramName != '?').and(paramName != \instrument).and(
					paramName != \def).and(paramName != \out).and(
					paramName != \gate).and(paramName != \in).and(
					paramName != \fadeTime), {

					//Temporary bus that the patternParamSynth for the fx will write to
					var patternParamBus = AlgaBus(server, paramNumChannels, paramRate);

					//Add bus to fxSynth at correct paramName
					fxSynthArgs = fxSynthArgs.add(paramName).add(patternParamBus.busArg);

					//Register bus to be freed
					patternBussesAndSynthsFx.add(patternParamBus);

					//Create a patternParamSynth for the fx param
					this.createPatternParamSynth(
						entry: entry,
						uniqueID: nil,
						paramName: paramName,
						paramNumChannels: paramNumChannels,
						paramRate: paramRate,
						paramDefault: paramDefault,
						patternInterpSumBus: patternParamBus, //pass the new Bus
						patternBussesAndSynths: patternBussesAndSynthsFx,
						isFX: true
					)
				});
			});
		});

		//If numChannels or rate mismatch between patternSynth -> fxSynth
		if((numChannelsToUse != fxInNumChannels).or(rateToUse != fxInRate), {
			//patternSynth -> \in
			var patternInterpInSynthSymbol = (
				"alga_pattern_" ++
				rateToUse ++
				numChannelsToUse ++
				"_" ++
				fxInRate ++
				fxInNumChannels ++
				"_fx"
			).asSymbol;

			//new Bus
			var patternInterpInBus = AlgaBus(server, fxInNumChannels, fxInRate);

			//Args to patternInterpInSynth
			var patternInterpInSynthArgs = [
				\in, patternBus.busArg,
				\out, patternInterpInBus.index
			];

			//Create patternInterpInSynth
			var patternInterpInSynth = AlgaSynth(
				patternInterpInSynthSymbol,
				patternInterpInSynthArgs,
				synthGroup,
				\addToTail,
				false
			);

			//Add Bus and Synth to patternBussesAndSynthsFx
			patternBussesAndSynthsFx.add(patternInterpInBus);
			patternBussesAndSynthsFx.add(patternInterpInSynth);

			//Add correct \in for fxSynth
			fxSynthArgs = fxSynthArgs.add(\in).add(patternInterpInBus.busArg)
		}, {
			//Same channels / rate: read from patternBus
			fxSynthArgs = fxSynthArgs.add(\in).add(patternBus.busArg)
		});

		//If numChannels or rate mismatch between fxSynth -> algaSynthBus
		if((fxNumChannels != numChannels).or(fxRate != rate), {
			//Create the Bus fxSynth will write to
			var fxBus = AlgaBus(server, fxNumChannels, fxRate);

			//fxSynth -> algaSynthBus (that's why it uses rate / numChannels)
			fxInterpSynthSymbol = (
				"alga_pattern_" ++
				fxRate ++
				fxNumChannels ++
				"_" ++
				rate ++
				numChannels ++
				"_fx"
			).asSymbol;

			//Set correct args
			fxInterpSynthArgs = [
				\in, fxBus.busArg,
				\out, algaSynthBus.index
			];

			//Add Bus to patternBussesAndSynthsFx
			patternBussesAndSynthsFx.add(fxBus);

			//Add correct \out for fxSynthArgs to fxBus
			fxSynthArgs = fxSynthArgs.add(\out).add(fxBus.index)
		}, {
			//Same channels / rate: write to algaSynthBus
			fxSynthArgs = fxSynthArgs.add(\out).add(algaSynthBus.index)
		});

		//The user's def
		fxSynthSymbol = def;

		//If not skipping the iteration
		if(skipIterationFX.not, {
			//Create fxSynth, \addToTail
			fxSynth = AlgaSynth(
				fxSynthSymbol,
				fxSynthArgs,
				synthGroup,
				\addToTail,
				false
			);

			//If not explicitFree, add fxSynth to the free mechanism.
			//If explicitFree, it will handle it by itself
			if(explicitFree.not, { patternBussesAndSynths.add(fxSynth) });

			//FUNDAMENTAL: add fxSynth to the algaPatternSynths so that
			//algaSynthBus is kept alive for the WHOLE duration of the fx too.
			algaPatternInterpStreams.algaPatternSynths.add(fxSynth);

			//If there was conversion, create the fxInterpSynth (needs to come after fxSynth !!!)
			if(fxInterpSynthSymbol != nil, {
				fxInterpSynth = AlgaSynth(
					fxInterpSynthSymbol,
					fxInterpSynthArgs,
					synthGroup,
					\addToTail,
					false
				);

				//Add to patternBussesAndSynthsFx
				patternBussesAndSynthsFx.add(fxInterpSynth);
			});
		});

		//Free all relative synths / busses on fxSynth free.
		//fxSynth is either freed by itself (if explicitFree) or when patternSynth is freed.
		onFXSynthFreeFunc = {
			//Free synths and busses
			patternBussesAndSynthsFx.do({ | synthOrBus | synthOrBus.free });

			//Remove fxSynth from algaPatternSynths
			algaPatternInterpStreams.algaPatternSynths.remove(fxSynth);
		};

		//Check if skipping the iteration for FX
		if(skipIterationFX.not, {
			//Execute func on fxSynth's free
			fxSynth.onFree(onFXSynthFreeFunc);

			//Use patternBus as \out for patternSynth
			patternSynthArgs = patternSynthArgs.add(\out).add(patternBus.index);
		}, {
			onFXSynthFreeFunc.value;
		});

		//Reset skipIterationFX
		skipIterationFX = false;

		//Return patternSynthArgs
		^patternSynthArgs;
	}

	//Create a temporary synth for \out connection
	createOutConnection { | algaOut, algaSynthBus, outTempBus, patternBussesAndSynths |
		case
		{ algaOut.isAlgaNode } {
			//Only if instantiated (or it will click)
			if(algaOut.algaInstantiatedAsReceiver(\in), {
				algaOut.receivePatternOutTempSynth(
					algaPattern: this,
					algaSynthBus: algaSynthBus,
					outTempBus: outTempBus,
					algaNumChannels: numChannels,
					algaRate: rate,
					patternBussesAndSynths: patternBussesAndSynths
				)
			});
		}
		{ algaOut.isAlgaOut } {
			var node  = algaOut.node;
			var param = algaOut.param;
			var scale = algaOut.scale;
			var chans = algaOut.chans;

			if(node.isAlgaNode, {
				//Only if instantiated (or it will click)
				if(node.algaInstantiatedAsReceiver(param), {
					node.receivePatternOutTempSynth(
						algaPattern: this,
						algaSynthBus: algaSynthBus,
						outTempBus: outTempBus,
						algaNumChannels: numChannels,
						algaRate: rate,
						param: param,
						patternBussesAndSynths: patternBussesAndSynths,
						chans: chans,
						scale: scale
					)
				});
			});
		};
	}

	//Add to currentActivePatternInterpSumBusses[paramName]
	addCurrentActivePatternInterpSumBussesForThisPatternSynth { | paramName, patternInterpSumBus |
		var currentActivePatternInterpSumBussesAtParam = currentActivePatternInterpSumBusses[paramName];
		if(currentActivePatternInterpSumBussesAtParam == nil, {
			currentActivePatternInterpSumBusses[paramName] = IdentitySet();
			currentActivePatternInterpSumBussesAtParam = currentActivePatternInterpSumBusses[paramName];
		});
		currentActivePatternInterpSumBussesAtParam.add(patternInterpSumBus);
	}

	//Remove from currentActivePatternInterpSumBusses[paramName] using latest ones
	removeCurrentActivePatternInterpSumBussesForThisPatternSynth { | patternInterpSumBusses |
		patternInterpSumBusses.keysValuesDo({ | paramName, patternInterpSumBus |
			var currentActivePatternInterpSumBussesAtParam = currentActivePatternInterpSumBusses[paramName];
			if(currentActivePatternInterpSumBussesAtParam != nil, {
				currentActivePatternInterpSumBussesAtParam.remove(patternInterpSumBus)
			});
			currentPatternBussesAndSynths.removeAt(patternInterpSumBus);
		});
	}

	//Create all needed Synths / Busses for an individual patternSynth
	createPatternSynth { | algaSynthDef, algaSynthBus,
		algaPatternInterpStreams, controlNamesToUse, fx, algaOut,
		mcSynthNum, mcEntries |
		//Used to check whether using a ListPattern of \defs
		var numChannelsToUse  = numChannels;
		var rateToUse = rate;

		//Flag to check for mismatches (and needed conversions) for numChannels / rates
		var numChannelsOrRateMismatch = false;

		//These will be populated and freed when the patternSynth is released
		var patternBussesAndSynths = IdentitySet(controlNames.size * 2);

		//args to patternSynth
		var patternSynthArgs = [ \gate, 1 ];

		//The actual synth that will be created
		var patternSynth;

		//All the patternInterpSumBusses here created
		var patternInterpSumBussesForThisPatternSynth = IdentityDictionary();

		//Valid \fx our \out
		var validFX = false;
		var validOut = false;

		//The final free func
		var onPatternSynthFreeFunc;

		//Check if it's a ListPattern and retrieve correct controlNames
		if(controlNamesList != nil, {
			controlNamesToUse = controlNamesList[algaSynthDef];
			if(controlNamesToUse == nil, { controlNamesToUse = controlNames });
		});

		//Loop over controlNames and create as many Busses and Synths as needed,
		//also considering interpolation / normalization
		controlNamesToUse.do({ | controlName |
			var paramName = controlName.name;
			var paramNumChannels = controlName.numChannels;
			var paramRate = controlName.rate;
			var paramDefault = controlName.defaultValue;

			//This is the interpBus for this param that all patternParamSynths will write to.
			//This will then be used for the actual normalization that happens in the normSynth
			//As with AlgaNode's, it needs one extra channel for the separate env.
			var patternInterpSumBus = AlgaBus(server, paramNumChannels + 1, paramRate);

			//This is the normBus that the normSynth will write to, and patternSynth will read from
			var patternParamNormBus = AlgaBus(server, paramNumChannels, paramRate);

			//Symbol for normSynth
			var patternParamNormSynthSymbol = (
				"alga_norm_" ++
				paramRate ++
				paramNumChannels
			).asSymbol;

			//Args for normSynth
			var patternParamNormSynthArgs = [
				\args, patternInterpSumBus.busArg,
				\out, patternParamNormBus.index,
				\fadeTime, 0
			];

			//The actual normSynth for this specific param.
			//The patternSynth will read from its output.
			var patternParamNormSynth = AlgaSynth(
				patternParamNormSynthSymbol,
				patternParamNormSynthArgs,
				normGroup,
				waitForInst: false
			);

			//All the current interpStreams for this param. Retrieve the entries,
			//which store all the senders to this param for the interpStream.
			var interpStreamsEntriesAtParam = algaPatternInterpStreams.entries[paramName];

			//Used in MC
			var mcEntriesAtParam;

			//scaleArray and chans
			var scaleArraysAndChansAtParam = algaPatternInterpStreams.scaleArraysAndChans[paramName];

			//sampleAndHold
			var sampleAndHold = algaPatternInterpStreams.sampleAndHolds[paramName];
			sampleAndHold = sampleAndHold ? false;

			//MultiChannel: extract from mcEntries
			if(useMultiChannelExpansion, {
				mcEntriesAtParam = mcEntries[paramName]
			});

			//Create all interp synths for current param
			this.createPatternParamSynths(
				paramName: paramName,
				paramNumChannels: paramNumChannels,
				paramRate: paramRate,
				paramDefault: paramDefault,
				patternInterpSumBus: patternInterpSumBus,
				patternBussesAndSynths: patternBussesAndSynths,
				interpStreamsEntriesAtParam: interpStreamsEntriesAtParam,
				scaleArraysAndChansAtParam: scaleArraysAndChansAtParam,
				sampleAndHold: sampleAndHold,
				algaPatternInterpStreams: algaPatternInterpStreams,
				mcSynthNum: mcSynthNum,
				mcEntriesAtParam: mcEntriesAtParam
			);

			//Read from patternParamNormBus
			patternSynthArgs = patternSynthArgs.add(paramName).add(patternParamNormBus.busArg);

			//Register normBus, normSynth, interSumBus to be freed
			patternBussesAndSynths.add(patternParamNormBus);
			patternBussesAndSynths.add(patternParamNormSynth);
			patternBussesAndSynths.add(patternInterpSumBus);

			//Latest patternInterpSumBus
			latestPatternInterpSumBusses[paramName] = patternInterpSumBus;

			//Add to currentPatternBussesAndSynths.
			//These are indexed with patternInterpSumBus in order to be retrieved for temporary synths
			currentPatternBussesAndSynths[patternInterpSumBus] = patternBussesAndSynths;

			//This is used for removal later on
			patternInterpSumBussesForThisPatternSynth[paramName] = patternInterpSumBus;

			//Current active patternInterpSumBusses at paramName
			this.addCurrentActivePatternInterpSumBussesForThisPatternSynth(
				paramName,
				patternInterpSumBus
			);
		});

		//If ListPattern, retrieve correct numChannels
		if(numChannelsList != nil, {
			numChannelsToUse = numChannelsList[algaSynthDef];
			if(numChannelsToUse == nil, { numChannelsToUse = numChannels });
		});

		//If ListPattern, retrieve correct rate
		if(rateList != nil, {
			rateToUse = rateList[algaSynthDef];
			if(rateToUse == nil, { rateToUse = rate });
		});

		//If there is a mismatch between numChannels / numChannelsToUse OR rate / rateToUse,
		//use numChannelsToUse and rateToUse to determine patternSynth's output.
		//Then, convert it to be used with algaSynthBus.
		if((numChannels != numChannelsToUse).or(rate != rateToUse), {
			numChannelsOrRateMismatch = true
		});

		//Extract fx multichannel
		if(useMultiChannelExpansion, {
			if(fx.isArray, {
				if(mcSynthNum != nil, { fx = fx[mcSynthNum] })
			});
		}, {
			//Or use first entry if not MC
			if(fx.isArray, { fx = fx[0] });
		});

		//Check validFX
		if((fx != nil).and(fx.isEvent), { validFX = true });

		//Extract out multichannel (not valid in FX)
		if(useMultiChannelExpansion, {
			if(algaOut.isArray, {
				if(mcSynthNum != nil, { algaOut = algaOut[mcSynthNum] })
			});
		}, {
			//Or use first entry if not MC
			if(algaOut.isArray, { algaOut = algaOut[0] });
		});

		//Check validOut
		if((algaOut != nil).and((algaOut.isAlgaOut).or(algaOut.isAlgaNode)), { validOut = true });

		//If fx
		if(validFX, {
			//This returns the patternSynthArgs with correct bus to write to (the fx one)
			patternSynthArgs = this.createFXSynthAndPatternSynths(
				fx: fx,
				numChannelsToUse: numChannelsToUse,
				rateToUse: rateToUse,
				algaSynthDef: algaSynthDef,
				algaSynthBus: algaSynthBus,
				algaPatternInterpStreams: algaPatternInterpStreams,
				patternSynthArgs: patternSynthArgs,
				patternBussesAndSynths: patternBussesAndSynths
			);
		}, {
			//Channels mismatch: pop a converter
			if(numChannelsOrRateMismatch, {
				var converterBus = AlgaBus(server, numChannelsToUse, rateToUse);
				var patternSynthConverterSymbol = (
					"alga_pattern_" ++
					rateToUse ++
					numChannelsToUse ++
					"_" ++
					rate ++
					numChannels ++
					"_fx"
				).asSymbol;
				var patternSynthConverter = AlgaSynth(
					patternSynthConverterSymbol,
					[ \in, converterBus.busArg, \out, algaSynthBus.index ],
					synthGroup,
					\addToTail,
					false
				);

				//Add the converter Bus / Synth to patternBussesAndSynths
				patternBussesAndSynths.add(converterBus);
				patternBussesAndSynths.add(patternSynthConverter);

				//patternSynth will write to converterBus
				patternSynthArgs = patternSynthArgs.add(\out).add(converterBus.index);
			}, {
				//Standard case: write directly to algaSynthBus
				patternSynthArgs = patternSynthArgs.add(\out).add(algaSynthBus.index);
			});
		});

		//Valid out
		if(validOut, {
			//New temp bus
			var outTempBus = AlgaBus(server, numChannels, rate);

			//Add temp bus to patternBussesAndSynths
			patternBussesAndSynths.add(outTempBus);

			//Create connection synth with the target
			this.createOutConnection(algaOut, algaSynthBus, outTempBus, patternBussesAndSynths);

			//Update the synthDef symbol to use the patternTempOut version
			algaSynthDef = (algaSynthDef.asString ++ "_patternTempOut").asSymbol;

			//Add patternTempOut writing to patternSynthArgs
			patternSynthArgs = patternSynthArgs.add(\patternTempOut).add(outTempBus.index);
		});

		//The actual patternSynth according to the user's def
		if(skipIteration.not, {
			patternSynth = AlgaSynth(
				algaSynthDef,
				patternSynthArgs,
				synthGroup,
				waitForInst: false
			);

			//Add pattern synth to algaPatternSynths, and free it when patternSynth gets freed
			algaPatternInterpStreams.algaPatternSynths.add(patternSynth);
		});

		//Free all normBusses, normSynths, interpBusses and interpSynths on patternSynth's release
		onPatternSynthFreeFunc = {
			//Free all Synths and Busses
			patternBussesAndSynths.do({ | synthOrBus | synthOrBus.free });

			//Remove the entry from algaPatternSynths
			algaPatternInterpStreams.algaPatternSynths.remove(patternSynth);

			//Remove all busses from currentActivePatternInterpSumBusses
			//and currentPatternBussesAndSynths
			this.removeCurrentActivePatternInterpSumBussesForThisPatternSynth(
				patternInterpSumBussesForThisPatternSynth
			);

			//Remove currentActivePatternParamSynths[patternInterpSumBus]
			patternInterpSumBussesForThisPatternSynth.do({ | patternInterpSumBus_tmp |
				currentActivePatternParamSynths.removeAt(patternInterpSumBus_tmp)
			});

			//IMPORTANT: free the unused interpBusses of the interpStreams.
			//This needs to happen on patternSynth's free as that's the notice
			//that no other synths will be using them. Also, this fixes the case where
			//patternSynth takes longer than \dur. We want to wait for the end of patternSynth
			//to free all used things!
			this.freeUnusedInterpBusses(algaPatternInterpStreams);
		};

		//If not skipping, execute on patternSynth's free.
		if(skipIteration.not, {
			patternSynth.onFree(onPatternSynthFreeFunc)
		}, {
			onPatternSynthFreeFunc.value;
		});

		//Update latest time
		latestPatternTime = this.clock.seconds;

		//Reset
		skipIteration = false;
	}

	//Calculate the MC mismatches and return a new array with all the correct settings.
	//Each entry will be used to create an individual patternSynth
	calculateMultiChannelMismatches { | controlNamesToUse, algaPatternInterpStreams,
		fx, algaOut |
		var numOfSynths = 0;
		var entries = IdentityDictionary();

		//Loop over the actual control names
		controlNamesToUse.do({ | controlName |
			var paramName = controlName.name;
			var paramNumChannels = controlName.numChannels;
			var interpStreamsEntriesAtParam = algaPatternInterpStreams.entries[paramName];
			if(interpStreamsEntriesAtParam != nil, {
				interpStreamsEntriesAtParam.keysValuesDo({ | uniqueID, entry |
					//Unpack Pattern value
					if(entry.isStream, { entry = entry.next });

					//Set entries at paramName
					entries[paramName] = entries[paramName] ? IdentityDictionary();

					//Use uniqueID
					entries[paramName][uniqueID] = [entry, paramNumChannels];

					//Retrieve the highest numOfSynths to spawn
					if(entry.isArray, {
						var arraySize = entry.size;
						if(arraySize > paramNumChannels, {
							numOfSynths = max(
								arraySize - (paramNumChannels - 1),
								numOfSynths
							)
						});
					});
				});
			});
		});

		//Also check \fx
		if(fx.isArray, {
			var arraySize = fx.size;
			entries[\fx] = entries[\fx] ? IdentityDictionary();
			numOfSynths = max(arraySize, numOfSynths);
			entries[\fx] = fx.reshape(numOfSynths);
		});

		//Also check \out
		if(algaOut.isArray, {
			var arraySize = algaOut.size;
			entries[\algaOut] = entries[\algaOut] ? IdentityDictionary();
			numOfSynths = max(arraySize, numOfSynths);
			entries[\algaOut] = algaOut.reshape(numOfSynths);
			if(arraySize > numOfSynths, { //needs to be re-done, overwritten
				entries[\fx] = fx.reshape(numOfSynths);
			});
		});

		//Loop over entries and distribute the values across the synths to spawn
		entries.keysValuesDo({ | paramName, entriesAtParamName |
			if((paramName != \fx).and(paramName != \algaOut), {
				entriesAtParamName.keysValuesDo({ | uniqueID, entryAndParamNumChannels |
					var entry = entryAndParamNumChannels[0];
					var paramNumChannels = entryAndParamNumChannels[1];
					var newEntry = Array.newClear(numOfSynths);
					if(numOfSynths > 0, {
						numOfSynths.do({ | i |
							if(entry.isArray, {
								var subEntry = entry[0..paramNumChannels-1]; //sub entry
								entry = entry[paramNumChannels..entry.size-1]; //shift
								if(subEntry.size == 1, { subEntry = subEntry[0] }); //extract single entries
								newEntry[i] = subEntry;
							}, {
								newEntry[i] = entry;
							});
						});
					}, {
						newEntry = entry;
					});
					entries[paramName][uniqueID] = newEntry;
				});
			});
		});

		//Add numOfSynths
		entries[\numOfSynths] = numOfSynths;

		//Return the correct entries
		^entries;
	}

	//Create all needed Synths for this Event. This is triggered by the \algaNote Event
	createEventSynths { | algaSynthDef, algaSynthBus,
		algaPatternInterpStreams, fx, algaOut |

		//Get controlNames and check if it's a ListPattern
		//(in case for multiple SynthDefs). Get the correct one if available.
		var controlNamesToUse = controlNames;
		if(controlNamesList != nil, {
			controlNamesToUse = controlNamesList[algaSynthDef];
			if(controlNamesToUse == nil, { controlNamesToUse = controlNames });
		});

		//Check MC mismatches and create patternSynths accordingly
		if(useMultiChannelExpansion, {
			//Each entry will be used to create a single patternSynth
			var entries = this.calculateMultiChannelMismatches(
				controlNamesToUse: controlNamesToUse,
				algaPatternInterpStreams: algaPatternInterpStreams,
				fx: fx,
				algaOut: algaOut
			);

			//Get numOfSynths
			var numOfSynths = entries[\numOfSynths] ? 0;

			//Remove it
			entries.removeAt(\numOfSynths);

			//Get fx
			if(entries[\fx] != nil, {
				fx = entries[\fx];
				entries.removeAt(\fx);
			});

			//Get out
			if(entries[\algaOut] != nil, {
				algaOut = entries[\algaOut];
				entries.removeAt(\algaOut);
			});

			//Create MC expansion. If it fails (numSynths == 0)
			//it will execute the next createPatternSynth, the one with no MC
			if(numOfSynths > 0, {
				numOfSynths.do({ | synthNum |
					this.createPatternSynth(
						algaSynthDef: algaSynthDef,
						algaSynthBus: algaSynthBus,
						algaPatternInterpStreams: algaPatternInterpStreams,
						controlNamesToUse: controlNamesToUse,
						fx: fx,
						algaOut: algaOut,
						mcSynthNum: synthNum,
						mcEntries: entries
					);
				});

				^this;
			}, {
				//numOfSynths == 0, mcSynthNum is then nil
				^this.createPatternSynth(
					algaSynthDef: algaSynthDef,
					algaSynthBus: algaSynthBus,
					algaPatternInterpStreams: algaPatternInterpStreams,
					controlNamesToUse: controlNamesToUse,
					fx: fx,
					algaOut: algaOut,
					mcSynthNum: nil,
					mcEntries: entries
				);
			});
		});

		//No MC expansion used: create the single patternSynth with all its params
		this.createPatternSynth(
			algaSynthDef: algaSynthDef,
			algaSynthBus: algaSynthBus,
			algaPatternInterpStreams: algaPatternInterpStreams,
			controlNamesToUse: controlNamesToUse,
			fx: fx,
			algaOut: algaOut
		)
	}

	//dispatchNode: first argument is an Event or SynthDef
	dispatchNode { | def, args, initGroups = false, replace = false, reset = false,
		keepChannelsMapping = false, outsMapping, keepScale = false, sched = 0 |

		//def: entry
		var defEntry = def[\def];
		if(defEntry == nil, {
			"AlgaPattern: no 'def' entry in the Event".error;
			^this;
		});

		//Store the Event
		eventPairs = def;

		//If there is a synth playing, set its algaInstantiated status to false:
		//this is mostly needed for .replace to work properly and wait for the new synth
		//to be algaInstantiated!
		if(synth != nil, { synth.algaInstantiated = false });

		//Parse reset
		this.parseResetOnReplace(reset);

		//Also store reset as it's needed to reset algaParams (\out, \fx, etc...)
		currentReset = reset;

		//Create args dict
		this.createDefArgs(args);

		//Reset all ListPattern vars (in case of dispatchListPattern, they will be created anew)
		numChannelsList = nil;
		rateList = nil;
		controlNamesList = nil;

		case
		{ defEntry.isSymbol } {
			^this.dispatchSynthDef(
				def: defEntry,
				initGroups: initGroups,
				replace: replace,
				keepChannelsMapping:keepChannelsMapping,
				keepScale:keepScale,
				sched:sched
			)
		}
		{ defEntry.isListPattern } {
			^this.dispatchListPattern(
				def: defEntry,
				initGroups: initGroups,
				replace: replace,
				keepChannelsMapping:keepChannelsMapping,
				keepScale:keepScale,
				sched:sched
			)
		};

		//No dispatchFunction, as the parsing has already happened and def contains only Symbols

		("AlgaPattern: '" ++ defEntry.asString ++ "' is an invalid 'def'").error;
	}

	//Overloaded function
	buildFromSynthDef { | initGroups = false, replace = false,
		keepChannelsMapping = false, keepScale = false, sched = 0 |

		var synthDescControlNames;

		//Detect if AlgaSynthDef can be freed automatically. Otherwise, error!
		if(synthDef.explicitFree.not, {
			("AlgaPattern: AlgaSynthDef '" ++ synthDef.name.asString ++ "' can't free itself: it doesn't implement any Done action.").error;
			^this
		});

		//Retrieve controlNames from SynthDesc
		synthDescControlNames = synthDef.asSynthDesc.controls;

		//Create controlNames
		this.createControlNamesAndParamsConnectionTime(synthDescControlNames);

		//Retrieve channels and rate
		numChannels = synthDef.numChannels;
		rate = synthDef.rate;

		//Sched must be a num
		sched = sched ? 0;

		//Generate outsMapping (for outsMapping connectinons)
		this.calculateOutsMapping(replace, keepChannelsMapping);

		//Create groups if needed
		if(initGroups, { this.createAllGroups });

		//Create synthBus for output
		//interpBusses are taken care of in createPatternInterpSynthAndBus.
		this.createSynthBus;

		//Create the actual pattern.
		this.createPattern(
			replace: replace,
			keepChannelsMapping: keepChannelsMapping,
			keepScale: keepScale,
			sched: sched
		);
	}

	//Build spec from ListPattern
	buildFromListPattern { | initGroups = false, replace = false,
		keepChannelsMapping = false, keepScale = false, sched = 0 |

		//Sched must be a num
		sched = sched ? 0;

		//Generate outsMapping (for outsMapping connectinons)
		if(this.calculateOutsMapping(replace, keepChannelsMapping) == nil, { ^this });

		//Create groups if needed
		if(initGroups, { this.createAllGroups });

		//Create synthBus for output
		//interpBusses are taken care of in createPatternInterpSynthAndBus.
		this.createSynthBus;

		//Create the actual pattern.
		this.createPattern(
			replace: replace,
			keepChannelsMapping: keepChannelsMapping,
			keepScale: keepScale,
			sched: sched
		);
	}

	//Check rates, numChannels, Symbols and controlNames
	checkListPatternValidityAndReturnControlNames { | listPattern |
		var numChannelsCount, rateCount;
		var controlNamesDict = IdentityDictionary();
		var controlNamesSum = Array.newClear;

		listPattern.list.do({ | entry, i |
			var synthDescEntry, synthDefEntry;
			var controlNamesEntry;
			var controlNamesListPatternDefaultsEntry;

			//If another ListPattern, recursively add stuff
			if(entry.isListPattern, {
				controlNamesSum = controlNamesSum.addAll(this.checkListPatternValidityAndReturnControlNames(entry));
			}, {
				//Only support Symbols (not Functions, too much of a PITA)
				if(entry.class != Symbol, {
					"AlgaPattern: the ListPattern defining 'def' can only contain Symbols pointing to valid AlgaSynthDefs".error;
					^nil;
				});

				synthDescEntry = SynthDescLib.global.at(entry);

				if(synthDescEntry == nil, {
					("AlgaPattern: Invalid AlgaSynthDef: '" ++ entry.asString ++ "'").error;
					^nil;
				});

				synthDefEntry = synthDescEntry.def;

				if(synthDefEntry.class != AlgaSynthDef, {
					("AlgaPattern: Invalid AlgaSynthDef: '" ++ entry.asString ++"'").error;
					^nil;
				});

				if(synthDefEntry.explicitFree.not, {
					("AlgaPattern: AlgaSynthDef '" ++ synthDefEntry.name.asString ++ "' can't free itself: it doesn't implement any Done action.").error;
					^nil
				});

				if(numChannelsCount == nil, {
					numChannelsCount = synthDefEntry.numChannels;
					numChannels = numChannelsCount
				});

				if(rateCount == nil, {
					rateCount = synthDefEntry.rate;
					rate = rateCount
				});

				//Use the highest count of numChannels as "main" one, using that one entry's rate
				if(synthDefEntry.numChannels > numChannelsCount, {
					numChannels = synthDefEntry.numChannels;
					rate = synthDefEntry.rate
				});

				//Store for next iteration
				numChannelsCount = synthDefEntry.numChannels;
				rateCount = synthDefEntry.rate;

				//Add numChannels and rate to numChannelsList and rateList
				numChannelsList[entry.asSymbol] = numChannelsCount;
				rateList[entry.asSymbol] = rateCount;

				//Retrieve controlNames
				controlNamesEntry = synthDescEntry.controls;

				//Create entry for controlNamesList
				controlNamesList[entry.asSymbol] = IdentityDictionary();

				//Check for duplicates and add correct controlName to controlNamesList[entry.asSymbol]
				controlNamesEntry.do({ | controlName |
					var name = controlName.name;
					if((name != \fadeTime).and(
						name != \out).and(
						name != \gate).and(
						name != '?'), {

						//Just check for duplicate names: we only need one entry per param name
						//for controlNamesSum.
						if(controlNamesDict[name] == nil, {
							controlNamesDict[name] = controlName;
							controlNamesSum = controlNamesSum.add(controlName);
						});

						//Add to IdentityDict for specific def / name combination
						controlNamesList[entry.asSymbol][name] = controlName;
					});
				});
			});
		});

		^controlNamesSum;
	}

	//Multiple Symbols over a ListPattern
	dispatchListPattern { | def, initGroups = false, replace = false,
		keepChannelsMapping = false, keepScale = false, sched = 0 |
		var controlNamesSum;
		var functionsAndListPattern;
		var functions;

		//Create numChannelsList, rateList and controlNamesList (they're nil otherwise)
		numChannelsList  = IdentityDictionary();
		rateList         = IdentityDictionary();
		controlNamesList = IdentityDictionary();

		//Check rates, numChannels, Symbols and controlNames
		controlNamesSum = this.checkListPatternValidityAndReturnControlNames(def);
		if(controlNamesSum != nil, {
			//Create controlNames from controlNamesSum
			this.createControlNamesAndParamsConnectionTime(controlNamesSum);

			//synthDef will be the ListPattern
			synthDef = def;

			//Substitute the eventPairs entry with the new ListPattern
			eventPairs[\def] = def;

			//Build pattern from ListPattern
			this.buildFromListPattern(
				initGroups: initGroups,
				replace: replace,
				keepChannelsMapping: keepChannelsMapping,
				keepScale: keepScale,
				sched: sched
			);
		});
	}

	//Create out: receivers
	createPatternOutReceivers { | prevPatternOutNodes |
		var time = currentPatternOutTime ? 0;

		//Fade out (also old synths)
		if(prevPatternOutNodes != nil, {
			prevPatternOutNodes.do({ | outNodeAndParam |
				var outNode = outNodeAndParam[0];
				var param = outNodeAndParam[1];
				scheduler.addAction(
					//condition: { outNode.algaInstantiatedAsReceiver(param) },
					func: {
						outNode.removePatternOutsAtParam(
							algaPattern: this,
							param: param,
							removePatternOutNodeFromDict: true,
							time: time,
						)
					}
				);
			});
		});

		//Fade in (also new synths)
		if(currentPatternOutNodes != nil, {
			currentPatternOutNodes.do({ | outNodeAndParam |
				var outNode = outNodeAndParam[0];
				var param = outNodeAndParam[1];
				scheduler.addAction(
					condition: { outNode.algaInstantiatedAsReceiver(param) },
					func: {
						outNode.receivePatternOutsAtParam(
							algaPattern: this,
							param: param,
							time: time
						)
					}
				)
			});
		});

		//Reset currentPatternOutTime
		currentPatternOutTime = 0;
	}

	//Reset specific algaParams (\out, \fx, etc...) and genericParams
	parseResetOnReplaceParams {
		case
		{ currentReset.isArray } {
			^currentReset.as(IdentitySet);
		}
		{ currentReset == true } {
			^true;
		};

		^nil
	}

	//Check if param is a \freq type
	isFreqParam { | param = \in |
		^(
			(param == \midinote).or(param == \ctranspose).or(
				param == \harmonic).or(param == \detune).or(param == \note).or(
				param == \root).or(param == \octave).or(param == \gtranspose).or(
				param == \stepsPerOctave).or(param == \octaveRatio).or(
				param == \degree).or(param == \scale).or(param == \mtranspose)
		)
	}

	//Store generic params for replaces
	storeCurrentGenericParams { | key, value |
		currentGenericParams = currentGenericParams ? IdentityDictionary();
		currentGenericParams[key] = value;
	}

	//Build the actual pattern
	createPattern { | replace = false, keepChannelsMapping = false, keepScale = false, sched |
		var foundDurOrDelta = false;
		var manualDur = false;
		var foundFX = false;
		var parsedFX;
		var parsedOut;
		var prevPatternOutNodes = currentPatternOutNodes.copy;
		var foundOut = false;
		var foundGenericParams = IdentitySet();
		var patternPairs = Array.newClear;

		//Create new interpStreams. NOTE that the Pfunc in dur uses this, as interpStreams
		//will be overwritten when using replace. This allows to separate the "global" one
		//from the one that's being created here.
		var newInterpStreams = AlgaPatternInterpStreams(this);

		//Loop over controlNames and retrieve which parameters the user has set explicitly.
		//All other parameters will be dealt with later.
		controlNames.do({ | controlName |
			var paramName = controlName.name;
			var paramValue = eventPairs[paramName]; //Retrieve it directly from eventPairs
			var chansMapping, scale;
			var addToPatternPairs = true;

			//if not set explicitly yet
			if(paramValue == nil, {
				//When replace, getDefaultOrArg will return LATEST set parameter, via replaceArgs
				paramValue = this.getDefaultOrArg(controlName, paramName, replace);

				//Don't add to patternPairs: it's not set by user
				addToPatternPairs = false;
			});

			//If replace, check if keeping chans / scale mappings
			if(replace, {
				if(keepChannelsMapping, { chansMapping = this.getParamChansMapping(paramName, paramValue) });
				if(keepScale, { scale = this.getParamScaling(paramName, paramValue) });
			});

			//Add to interpStream (which also creates interpBus / interpSynth).
			//These are unscheduled, as it's best to just create them asap.
			//Only the pattern synths need to be scheduled
			newInterpStreams.add(
				entry: paramValue,
				controlName: controlName,
				chans: chansMapping,
				scale: scale,
				sampleAndHold: false,
				time: 0
			);

			//Add to patternPairs so that it's available for Pfunc { | event | }
			if(addToPatternPairs, {
				patternPairs = patternPairs.add(paramName).add(paramValue);
			});

			//Remove param entry from eventPairs
			eventPairs[paramName] = nil;

			//Add the entry to defaults
			defArgs[paramName] = paramValue;
		});

		//Loop over all other input from the user, setting all entries that are not part of controlNames
		eventPairs.keysValuesDo({ | paramName, value |
			var isAlgaParam = false;

			//Add \def key as \instrument
			if(paramName == \def, {
				patternPairs = patternPairs.add(\instrument).add(value);
				isAlgaParam = true;
			});

			//Found \dur or \delta
			if((paramName == \dur).or(paramName == \delta), {
				if((value.isSymbol).or(value.isNil), {
					//Using a symbol (like, \manual) as \dur.
					//Nil doesn't work as it won't even add to the Event
					manualDur = true;
				}, {
					foundDurOrDelta = true;
					this.setDur(value, newInterpStreams);
				});
				isAlgaParam = true;
			});

			//Add \fx key (parsing everything correctly)
			if(paramName == \fx, {
				parsedFX = value;
				if(parsedFX != nil, {
					patternPairs = patternPairs.add(\fx).add(parsedFX);
					foundFX = true;
				});
				isAlgaParam = true;
			});

			//Add \out key
			if(paramName == \out, {
				parsedOut = value;
				if(parsedOut != nil, {
					patternPairs = patternPairs.add(\algaOut).add(parsedOut); //can't use \out
					foundOut = true;
				});
				isAlgaParam = true;
			});

			//All other entries that user wants to set and retrieve from within the pattern.
			//This includes things like \lag and \timingOffset
			if(isAlgaParam.not, {
				patternPairs = patternPairs.add(paramName).add(value);
				this.storeCurrentGenericParams(paramName, value); //Store it for replaces
				foundGenericParams.add(paramName);
			});
		});

		//Store current FX for replaces
		if(foundFX, { currentFX = parsedFX });

		//Store current out for replaces
		if(foundOut, { currentOut = parsedOut });

		//If no dur and replace, get it from previous interpStreams
		if(replace, {
			//Check reset for alga params
			var resetSet = this.parseResetOnReplaceParams;
			if(resetSet != nil, {
				case
				{ resetSet.class == IdentitySet } {
					//reset \fx
					if((foundFX.not).and(resetSet.findMatch(\fx) != nil), {
						parsedFX = nil; currentFX = nil
					});

					//reset \out
					if((foundOut.not).and(resetSet.findMatch(\out) != nil), {
						parsedOut = nil; currentOut = nil; currentPatternOutNodes = nil
					});

					//reset \dur
					if((foundDurOrDelta.not).and(resetSet.findMatch(\dur) != nil), {
						interpStreams = nil;
					});

					//reset generic params
					resetSet.do({ | entry | currentGenericParams.removeAt(entry) });
				}
				{ resetSet == true } {
					parsedFX = nil; currentFX = nil; //reset \fx
					parsedOut = nil; currentOut = nil; currentPatternOutNodes = nil; //reset \out
					interpStreams = nil; //reset \dur

					//reset all generic params
					if(currentGenericParams != nil, { currentGenericParams.clear });
				};
			});

			//Set dur according to previous one
			if(foundDurOrDelta.not, {
				if((interpStreams == nil).or(algaWasBeingCleared), {
					this.setDur(1, newInterpStreams)
				}, {
					this.setDur(interpStreams.dur, newInterpStreams)
				});
			});

			//No \fx from user, use currentFX if available
			if(foundFX.not, {
				if(currentFX != nil, {
					patternPairs = patternPairs.add(\fx).add(currentFX);
				});
			});

			//No \out from user, use currentOut if available
			if(foundOut.not, {
				if(currentOut != nil, {
					patternPairs = patternPairs.add(\algaOut).add(currentOut);
				});
			});

			//Add old genericParams
			if(currentGenericParams != nil, {
				if(currentGenericParams.size > 0, {
					currentGenericParams.keysValuesDo({ | key, value |
						//If that specific generic param hasn't been set from user,
						//use the one from currentGenericParams if available
						if(foundGenericParams.findMatch(key) == nil, {
							patternPairs = patternPairs.add(key).add(value)
						})
					})
				})
			});
		}, {
			//Else, default it to 1
			if(foundDurOrDelta.not, { this.setDur(1, newInterpStreams) });
		});

		//Set the correct synthBus in newInterpStreams!!!
		//This is fundamental for the freeing mechanism in stopPatternAndFreeSynths to work correctly
		//with the freeAllSynthsAndBussesOnReplace function call
		newInterpStreams.algaSynthBus = this.synthBus;

		//Add \type, \algaNode, and all things related to
		//the context of this AlgaPattern
		patternPairs = patternPairs.addAll([
			\type, \algaNote,
			\algaPattern, this,
			\algaSynthBus, this.synthBus, //Lock current one: will work on .replace
			\algaPatternServer, server,
			\algaPatternClock, this.clock,
			\algaPatternInterpStreams, newInterpStreams //Lock current one: will work on .replace
		]);

		//Manual or automatic dur management
		if(manualDur.not, {
			//Pfuncn allows to modify the value
			patternPairs = patternPairs.add(\dur).add(
				Pfuncn( { newInterpStreams.dur.next }, inf)
			);
		});

		//Create the Pattern by calling .next from the streams
		pattern = Pbind(*patternPairs);
		patternAsStream = pattern.algaAsStream; //Needed for things like dur: \none

		//Determine if \out interpolation is required
		this.createPatternOutReceivers(prevPatternOutNodes);

		//Schedule the start of the pattern on the AlgaScheduler. All the rest in this
		//createPattern function is non scheduled as it it better to create it right away.
		if(manualDur.not, {
			scheduler.addAction(
				func: {
					newInterpStreams.playAlgaReschedulingEventStreamPlayer(pattern, this.clock);
				},
				sched: sched
			);
		});

		//Update latest interpStreams
		interpStreams = newInterpStreams;
	}

	//Parse a single \fx event
	parseFXEvent { | value, functionSynthDefDict |
		var foundInParam = false;
		var synthDescFx, synthDefFx, controlNamesFx;
		var isFunction = false;
		var def;

		//Find \def
		def = value[\def];
		if(def == nil, {
			("AlgaPattern: no 'def' entry in 'fx': '" ++ value.asString ++ "'").error;
			^nil
		});

		//If it's a Function, send def to server and replace entries
		if(def.isFunction, {
			var defName = ("alga_" ++ UniqueID.next).asSymbol;

			//The synthDef will be sent later! just use the def and the desc for now
			synthDefFx = AlgaSynthDef(
				defName,
				def
			).synthDef; //Ignore AlgaSynthDefSpec

			//Get the desc
			synthDescFx = synthDefFx.asSynthDesc;

			//Important: NO sampleAccurate (it's only needed for the triggered pattern synths)
			functionSynthDefDict[defName] = synthDefFx;

			//Replace the def: with the symbol
			def = defName;
			value[\def] = defName;

			isFunction = true;
		});

		//Don't support ListPatterns for now
		if(def.isSymbol.not, {
			("AlgaPattern: 'fx' only supports Symbols and Functions as 'def'").error;
			^nil
		});

		//Check that \def is valid
		if(isFunction.not, {
			synthDescFx = SynthDescLib.global.at(def);
		});

		if(synthDescFx == nil, {
			("AlgaPattern: Invalid AlgaSynthDef in 'fx': '" ++ def.asString ++ "'").error;
			^nil;
		});

		if(isFunction.not, {
			synthDefFx = synthDescFx.def;
		});

		if(synthDefFx.class != AlgaSynthDef, {
			("AlgaPattern: Invalid AlgaSynthDef in 'fx': '" ++ def.asString ++"'").error;
			^nil;
		});

		if(synthDefFx.sampleAccurate, {
			("AlgaPattern: AlgaSynthDef in 'fx': '" ++ def.asString ++"' has 'sampleAccurate' on. This causes synchronization errors").warn;
		});

		controlNamesFx = synthDescFx.controls;
		controlNamesFx.do({ | controlName |
			if(controlName.name == \in, {
				foundInParam = true;
				//Pass numChannels / rate of in param to Event
				value[\inNumChannels] = controlName.numChannels;
				value[\inRate] = controlName.rate;
			})
		});

		//Not found the \in parameter
		if(foundInParam.not, {
			("AlgaPattern: Invalid AlgaSynthDef in 'fx': '" ++ def.asString ++ "': It does not provide an 'in' parameter").error;
			^nil;
		});

		//Pass controlNames / numChannels / rate to Event
		value[\controlNames] = controlNamesFx;
		value[\numChannels] = synthDefFx.numChannels;
		value[\rate] = synthDefFx.rate;

		//Pass explicitFree to Event
		value[\explicitFree] = synthDefFx.explicitFree;

		//Loop over the event and parse ListPatterns / AlgaTemps. Also use as Stream for the final entry.
		value.keysValuesDo({ | key, entry |
			var parsedEntry = entry;
			if(parsedEntry.isListPattern, { parsedEntry = this.parseListPatternParam(parsedEntry, functionSynthDefDict) });
			if(parsedEntry.isAlgaTemp, { parsedEntry = this.parseAlgaTempParam(parsedEntry, functionSynthDefDict) });
			value[key] = parsedEntry.algaAsStream;
		});

		^value;
	}

	//Parse the \fx key
	parseFX { | value, functionSynthDefDict |
		case

		//Single Event
		{ value.isEvent } {
			^this.parseFXEvent(value, functionSynthDefDict);
		}

		//If individual Symbol, if it's in DescLib, use it as Event. Otherwise, passthrough (like, \none, \dry)
		{ value.isSymbol } {
			if(SynthDescLib.global.at(value) != nil, {
				^this.parseFXEvent((def: value), functionSynthDefDict)
			});
			^value
		}

		//If individual Function, wrap in Event
		{ value.isFunction } {
			^this.parseFXEvent((def: value), functionSynthDefDict)
		}

		//ListPattern of any of the above
		{ value.isListPattern } {
			value.list.do({ | listEntry, i |
				var result = this.parseFX(listEntry, functionSynthDefDict); //recursive, so it picks up other ListPatterns if needed
				if(result == nil, { ^nil });
				value.list[i] = result;
			});
			^value;
		}

		//Array
		{ value.isArray } {
			value.do({ | entry, i |
				var result = this.parseFX(entry, functionSynthDefDict);
				if(result == nil, { ^nil });
				value[i] = result;
			});
			^value;
		};

		//Fallback
		("AlgaPattern: invalid Class for the 'fx' entry: '" ++ value.class.asString ++ "'").error;
		^nil;
	}

	//Parse a single \out event
	parseOutAlgaOut { | value, alreadyParsed |
		var node = value.node;
		var param = value.param;

		if(param == nil, param = \in);
		if(param.class != Symbol, {
			"AlgaPattern: the 'param' argument in AlgaOut can only be a Symbol. Using 'in'".error;
			param = \in
		});

		case
		{ node.isAlgaNode } {
			if(alreadyParsed[node] == nil, {
				if(node.isAlgaPattern, {
					"AlgaPattern: the 'out' parameter only supports AlgaNodes, not AlgaPatterns".error;
					^nil
				});
				alreadyParsed[node] = true;
				currentPatternOutNodes.add([node, param]);
			});
		}
		{ node.isListPattern } {
			node.list.do({ | listEntry, i |
				node.list[i] = this.parseOut(listEntry, alreadyParsed)
			});
		};

		^value.algaAsStream;
	}

	//Parse the \out key
	parseOut { | value, alreadyParsed |
		//Reset currentPatternOutNodes
		currentPatternOutNodes = currentPatternOutNodes ? IdentitySet();

		alreadyParsed = alreadyParsed ? IdentityDictionary();

		case
		{ value.isAlgaNode } {
			if(alreadyParsed[value] == nil, {
				if(value.isAlgaPattern, {
					"AlgaPattern: the 'out' parameter only supports AlgaNodes, not AlgaPatterns".error;
					^nil
				});
				alreadyParsed[value] = true;
				currentPatternOutNodes.add([value, \in]);
			});
			^value
		}
		{ value.isAlgaOut } {
			^this.parseOutAlgaOut(value, alreadyParsed);
		}
		{ value.isListPattern } {
			value.list.do({ | listEntry, i |
				var result = this.parseOut(listEntry, alreadyParsed); //recursive, so it picks up other ListPatterns if needed
				if(result == nil, { ^nil });
				value.list[i] = result;
			});
			^value.algaAsStream; //return the Stream
		}
		{ value.isArray } {
			value.do({ | entry, i |
				var result = this.parseOut(entry, alreadyParsed);
				if(result == nil, { ^nil });
				value[i] = result;
			});
			^value;
		}
		//This can be used to pass Symbols like \none or \dry to just passthrough the sound
		{ value.isSymbol } {
			^value
		};

		//Fallback
		("AlgaPattern: invalid Class for the 'out' entry: '" ++ value.class.asString ++ "'").error;
		^nil;
	}

	//Parse a ListPattern
	parseListPatternParam { | listPattern, functionSynthDefDict |
		listPattern.list.do({ | listEntry, i |
			if(listEntry.isListPattern, {
				listPattern.list[i] = this.parseListPatternParam(listEntry, functionSynthDefDict)
			});
			if(listEntry.isAlgaTemp, {
				listPattern.list[i] = this.parseAlgaTempParam(listEntry, functionSynthDefDict)
			});
		});
		^listPattern;
	}

	//Parse a param looking for AlgaTemps and ListPatterns
	parseAlgaTempListPatternParam { | value, functionSynthDefDict |

		//Used in from {}
		var returnBoth = false;
		if(functionSynthDefDict == nil, {
			returnBoth = true;
			functionSynthDefDict = IdentityDictionary();
		});

		case
		{ value.class == AlgaTemp } {
			value = this.parseAlgaTempParam(value, functionSynthDefDict)
		}
		{ value.isListPattern } {
			value = this.parseListPatternParam(value, functionSynthDefDict)
		};

		//Used in from {}
		if(returnBoth, {
			^[value, functionSynthDefDict]
		}, {
			^value
		})
	}

	//Parse a Function \def entry
	parseFunctionDefEntry { | def, functionSynthDefDict |
		//New defName
		var defName = ("alga_" ++ UniqueID.next).asSymbol;

		//Important: use sampleAccurate!
		functionSynthDefDict[defName] = AlgaSynthDef(
			defName,
			def,
			outsMapping: outsMapping,
			sampleAccurate: true
		); //Important: it returns AlgaSynthDefSpec (for pattern_out)

		//Return the Symbol
		^defName
	}

	//Parse a ListPattern \def entry
	parseListPatternDefEntry { | def, functionSynthDefDict |
		def.list.do({ | entry, i |
			def.list[i] = this.parseDefEntry(entry, functionSynthDefDict)
		});

		^def
	}

	//Parse the \def entry
	parseDefEntry { | def, functionSynthDefDict |
		case
		{ def.isFunction } {
			^this.parseFunctionDefEntry(def, functionSynthDefDict)
		}
		{ def.isListPattern } {
			^this.parseListPatternDefEntry(def, functionSynthDefDict)
		};

		^def
	}

	//Parse an entire def
	parseDef { | def |
		var defDef;
		var defFX;
		var defOut;

		//Store [defName] -> AlgaSynthDef
		var functionSynthDefDict = IdentityDictionary();

		//Wrap in an Event if not an Event already
		if(def.class != Event, { def = (def: def) });

		//Retrieve entries that need specific parsing
		defDef = def[\def];
		defFX  = def[\fx];
		defOut = def[\out];

		//Return nil if no def
		if(defDef == nil, {
			"AlgaPattern: the Event does not provide a 'def' entry".error;
			^nil
		});

		//Parse and replace \def
		def[\def] = this.parseDefEntry(defDef, functionSynthDefDict);

		//Parse \fx
		if(defFX != nil, { def[\fx] = this.parseFX(defFX, functionSynthDefDict) });

		//Parse \out
		if(defOut != nil, { def[\out] = this.parseOut(defOut) });

		//Parse all the other entries looking for AlgaTemps / ListPatterns
		def.keysValuesDo({ | key, value |
			def[key] = this.parseAlgaTempListPatternParam(value, functionSynthDefDict)
		});

		^[def, functionSynthDefDict];
	}

	//Get valid synthDef name
	getSynthDef {
		if(synthDef.class == AlgaSynthDef, {
			//Normal synthDef
			^synthDef.name
		}, {
			//ListPatterns
			^synthDef
		});
	}

	//Interpolate dur, either replace OR substitute at sched
	interpolateDur { | value, time, sched |
		if(replaceDur, {
			("AlgaPattern: 'dur' interpolation is not supported yet. Running 'replace' instead.").warn;
			^this.replace(
				def: (def: this.getSynthDef, dur: value),
				time: time,
				sched: sched
			);
		}, {
			if(sched == nil, { sched = 0 });
			("AlgaPattern: 'dur' interpolation is not supported yet. Rescheduling 'dur' at the " ++ sched ++ " quantization.").warn;
			^this.setDurAtSched(value, sched);
		});
	}

	//Interpolate def == replace
	interpolateDef { | def, time, sched |
		"AlgaPattern: changing the 'def' key. This will trigger 'replace'.".warn;
		^this.replace(
			def: (def: def),
			time: time,
			sched: sched
		);
	}

	//Buffer == replace
	interpolateBuffer { | sender, param, time, sched |
		("AlgaPattern: changing a Buffer parameter: '" + param.asString ++ ". This will trigger 'replace'.").warn;
		^this.replace(
			def: (def: this.getSynthDef, (param): sender), //escape param with ()
			time: time,
			sched: sched
		);
	}

	//Interpolate fx == replace
	interpolateFX { | value, time, sched |
		"AlgaPattern: changing the 'fx' key. This will trigger 'replace'.".warn;
		^this.replace(
			def: (def: this.getSynthDef, fx: value),
			time: time,
			sched: sched
		);
	}

	//Interpolate out == replace
	interpolateOut { | value, time, sched |
		"AlgaPattern: changing the 'out' key. This will trigger 'replace'.".warn;
		^this.replace(
			def: (def: this.getSynthDef, out: value),
			time: time,
			sched: sched
		);
	}

	//Interpolate a parameter that is not in controlNames (like \lag)
	interpolateGenericParam { | sender, param, time, sched |
		("AlgaPattern: changing the '" ++ param.asString ++ "' key, which is not present in the AlgaSynthDef. This will trigger 'replace'.").warn;
		^this.replace(
			def: (def: this.getSynthDef, (param): sender), //escape param with ()
			time: time,
			sched: sched
		);
	}

	//ListPattern that contains Buffers
	patternOrAlgaPatternArgContainsBuffers { | pattern |
		if(pattern.isAlgaArg, { if(pattern.sender.isBuffer, { ^true }) });
		if(pattern.isListPattern, {
			pattern.list.do({ | entry |
				if(entry.isBuffer, { ^true });
				if(entry.isAlgaArg, { if(entry.sender.isBuffer, { ^true }) });
			});
		});
		^false
	}

	//<<, <<+ and <|
	makeConnectionInner { | param = \in, sender, senderChansMapping, scale, sampleAndHold, time = 0 |
		var isDefault = false;
		var paramConnectionTime = paramsConnectionTime[param];
		var controlName;
		if(paramConnectionTime == nil, { paramConnectionTime = connectionTime });
		if(paramConnectionTime < 0, { paramConnectionTime = connectionTime });
		time = time ? paramConnectionTime;

		//This mostly happens on .resetParam. Try to get the default value from defArgs,
		//restoring the original Pattern's parameter. If it will be nil, the SynthDef's
		//default will eventually be used in the pattern loop.
		if(sender == nil, {
			isDefault = true;
			sender = defArgs[param];
		});

		//Check valid class
		if(isDefault.not, {
			if((sender.isAlgaNode.not).and(sender.isPattern.not).and(
				sender.isAlgaArg.not).and(
				sender.isAlgaTemp.not).and(
				sender.isNumberOrArray.not).and(sender.isBuffer.not), {
				"AlgaPattern: makeConnection only works with AlgaNodes, AlgaPatterns, AlgaPatternArgs, AlgaTemps, Patterns, Numbers, Arrays and Buffers".error;
				^this;
			});
		});

		//Add scaling to Dicts
		if(scale != nil, { this.addScaling(param, sender, scale) });

		//Add to interpStreams (which also creates interpBus / interpSynth)
		interpStreams.add(
			entry: sender,
			controlName: controlNames[param],
			chans: senderChansMapping,
			scale: scale,
			sampleAndHold: sampleAndHold,
			time: time
		);
	}

	//<<, <<+ and <|
	makeConnection { | sender, param = \in, replace = false, mix = false,
		replaceMix = false, senderChansMapping, scale, sampleAndHold, time, sched |

		//Default to false
		sampleAndHold = sampleAndHold ? false;

		//Check if it's boolean
		if((sampleAndHold != true).and(sampleAndHold != false), {
			"AlgaPattern: sampleAndHold must be a boolean value".error;
			^this
		});

		//Special case: ListPattern with Buffers
		if(this.patternOrAlgaPatternArgContainsBuffers(sender), {
			^this.interpolateBuffer(sender, param, time, sched)
		});

		//Check parameter in controlNames
		if(this.checkParamExists(param).not, {
			("AlgaPattern: '" ++ param ++ "' is not a valid parameter, it is not defined in the AlgaSynthDef.").error;
			^this
		});

		//All other cases
		if(this.algaCleared.not.and(sender.algaCleared.not).and(sender.algaToBeCleared.not), {
			scheduler.addAction(
				condition: { (this.algaInstantiatedAsReceiver(param, sender, false)).and(sender.algaInstantiatedAsSender) },
				func: {
					this.makeConnectionInner(
						param: param,
						sender: sender,
						senderChansMapping: senderChansMapping,
						scale: scale,
						sampleAndHold: sampleAndHold,
						time: time
					)
				},
				sched: sched,
				topPriority: true //This is essential for scheduled times to work correctly!
			)
		});
	}

	//from implementation
	fromInner { | sender, param = \in, chans, scale, sampleAndHold, time, sched |
		//delta == dur
		if(param == \delta, { param = \dur });

		//Special case, \dur
		if(param == \dur, {
			^this.interpolateDur(sender, time, sched);
		});

		//Special case, \def
		if(param == \def, {
			^this.interpolateDef(sender, time, sched);
		});

		//Special case, \fx
		if(param == \fx, {
			^this.interpolateFX(sender, time, sched);
		});

		//Special case, \out
		if(param == \out, {
			^this.interpolateOut(sender, time, sched);
		});

		//Entry is a Buffer == replace
		if(sender.isBuffer, {
			^this.interpolateBuffer(sender, param, time, sched);
		});

		//Param is not in controlNames. Probably setting another kind of parameter (like \lag)
		if(controlNames[param] == nil, {
			^this.interpolateGenericParam(sender, param, time, sched);
		});

		//Force Pattern / AlgaArg / AlgaTemp dispatch
		if((sender.isPattern).or(sender.isAlgaArg).or(sender.isAlgaTemp), {
			^this.makeConnection(
				sender: sender, param: param, senderChansMapping: chans,
				scale: scale, sampleAndHold: sampleAndHold, time: time, sched: sched
			);
		});

		//Standard cases
		if(sender.isAlgaNode, {
			if(this.server != sender.server, {
				("AlgaPattern: trying to enstablish a connection between two AlgaNodes on different servers").error;
				^this;
			});
			this.makeConnection(
				sender: sender, param: param, senderChansMapping: chans,
				scale: scale, time: time, sampleAndHold: sampleAndHold, sched: sched
			);
		}, {
			//sender == symbol is used for \def
			if(sender.isNumberOrArray, {
				this.makeConnection(
					sender: sender, param: param, senderChansMapping: chans,
					scale: scale, time: time, sampleAndHold: sampleAndHold, sched: sched
				);
			}, {
				("AlgaPattern: trying to enstablish a connection from an invalid class: " ++ sender.class).error;
			});
		});
	}

	//Only from is needed: to already calls into makeConnection
	from { | sender, param = \in, chans, scale, sampleAndHold, time, sched |
		//Parse the sender looking for AlgaTemps and ListPatterns
		var senderAndFunctionSynthDefDict = this.parseAlgaTempListPatternParam(sender);
		var functionSynthDefDict;

		//Unpack
		sender = senderAndFunctionSynthDefDict[0];
		functionSynthDefDict = senderAndFunctionSynthDefDict[1];

		//If sender is nil, don't do anything
		if(sender == nil, { ^this });

		//If needed, it will compile the AlgaSynthDefs in functionSynthDefDict and wait before executing func.
		//Otherwise, it will just execute func
		^this.compileFunctionSynthDefDictIfNeeded(
			func: {
				this.fromInner(
					sender: sender,
					param: param,
					chans: chans,
					scale: scale,
					sampleAndHold: sampleAndHold,
					time: time,
					sched: sched
				)
			},
			functionSynthDefDict: functionSynthDefDict
		)
	}

	//Don't support <<+ for now
	mixFrom { | sender, param = \in, inChans, scale, time |
		"AlgaPattern: mixFrom is not supported yet".error;
	}

	// <<| \param (goes back to defaults)
	//When sender is nil in makeConnection, the default value will be used
	resetParam { | param = \in, sampleAndHold, time, sched |
		this.makeConnection(
			sender: nil,
			param: param,
			sampleAndHold: sampleAndHold,
			time: time,
			sched: sched
		)
	}

	//Alias for resetParam
	reset { | param = \in, sampleAndHold, time, sched |
		this.resetParam(param, sampleAndHold, time, sched)
	}

	//Replace: run parsing of def before running (so the SynthDefs of Functions are sent right away)
	replace { | def, args, time, sched, outsMapping, reset = false, keepOutsMappingIn = true,
		keepOutsMappingOut = true, keepScalesIn = true, keepScalesOut = true |

		//Parse the def
		var defAndFunctionSynthDefDict = this.parseDef(def);
		var functionSynthDefDict;

		//Unpack
		def = defAndFunctionSynthDefDict[0];
		functionSynthDefDict = defAndFunctionSynthDefDict[1];

		//If needed, it will compile the AlgaSynthDefs in functionSynthDefDict and wait before executing func.
		//Otherwise, it will just execute func
		^this.compileFunctionSynthDefDictIfNeeded(
			func: {
				super.replace(
					def: def,
					args: args,
					time: time,
					sched: sched,
					outsMapping: outsMapping,
					reset: reset,
					keepOutsMappingIn: keepOutsMappingIn,
					keepOutsMappingOut: keepOutsMappingOut,
					keepScalesIn: keepScalesIn,
					keepScalesOut: keepScalesOut
				)
			},
			functionSynthDefDict: functionSynthDefDict
		)
	}

	//IMPORTANT: this function must be empty. It's called from replaceInner, but synthBus is actually
	//still being used by the pattern. It should only be freed when the pattern is freed, as it's done
	//in the stopPatternAndFreeSynths function. LEAVE THIS FUNCTION EMPTY, OR FAST PATTERNS WILL BUG OUT!!!
	freeAllBusses { | now = false, time | }

	//Called from replaceInner. freeInterpNormSynths is not used for AlgaPatterns
	freeAllSynths { | useConnectionTime = true, now = true, time |
		this.stopPatternAndFreeSynths(now, time);
	}

	//Used when replacing. Free synths and stop the current running pattern
	stopPatternAndFreeSynths { | now = true, time |
		currentPatternOutTime = time; //store time for \out
		if(interpStreams != nil, {
			if(now, {
				interpStreams.algaReschedulingEventStreamPlayer.stop;
				//freeAllSynthsAndBussesOnReplace MUST come after algaReschedulingEventStreamPlayer.stop!
				interpStreams.freeAllSynthsAndBussesOnReplace;
			}, {
				var interpStreamsOld = interpStreams;
				if(time == nil, { time = longestWaitTime });
				if(interpStreamsOld != nil, {
					fork {
						(time + 1.0).wait;
						interpStreamsOld.algaReschedulingEventStreamPlayer.stop;
						//freeAllSynthsAndBussesOnReplace MUST come after algaReschedulingEventStreamPlayer.stop!
						interpStreamsOld.freeAllSynthsAndBussesOnReplace;
					}
				});
			});
		});
	}

	//Manually advance the pattern. 'next' as function name won't work as it's reserved, apparently
	advance { | sched = 0 |
		sched = sched ? 0;
		if(patternAsStream != nil, {
			//If sched is 0, go right away: user might have its own scheduling setup
			if(sched == 0, {
				patternAsStream.next(()).play; //Empty event as protoEvent!
			}, {
				scheduler.addAction(
					func: {
						patternAsStream.next(()).play; //Empty event as protoEvent!
					},
					sched: sched
				);
			});
		});
	}

	//Alias of advance
	step { | sched | this.advance(sched) }

	//Stop pattern
	stopPattern { | sched |
		sched = sched ? 0;
		if(sched == 0, {
			interpStreams.algaReschedulingEventStreamPlayer.stop
		}, {
			scheduler.addAction(
				func: {
					interpStreams.algaReschedulingEventStreamPlayer.stop
				},
				sched: sched
			)
		});
	}

	//Resume pattern
	resumePattern { | sched |
		sched = sched ? 0;
		if(sched == 0, {
			interpStreams.algaReschedulingEventStreamPlayer.play
		}, {
			scheduler.addAction(
				func: {
					interpStreams.algaReschedulingEventStreamPlayer.play
				},
				sched: sched
			)
		});
	}

	//Set dur at sched
	setDurAtSched { | value, sched |
		//Add to scheduler just to make cascadeMode work
		scheduler.addAction(
			condition: { this.algaInstantiated },
			func: {
				var algaReschedulingEventStreamPlayer = interpStreams.algaReschedulingEventStreamPlayer;
				if(algaReschedulingEventStreamPlayer != nil, {
					algaReschedulingEventStreamPlayer.rescheduleAtQuant(sched, { this.setDur(value) });
				})
			}
		);
	}

	//stop and reschedule in the future
	reschedule { | sched = 0 |
		interpStreams.algaReschedulingEventStreamPlayer.reschedule(sched);
	}

	//Check all entries in controlNamesList aswell
	checkParamExists { | param = \in |
		//Standard
		if(controlNamesList == nil, {
			if(controlNames[param] == nil, { ^false });
			^true;
		});

		//ListPattern as 'def'
		controlNamesList.do({ | controlNamesEntry |
			if(controlNames[param] != nil, { ^true });
		});

		^false
	}

	//Add entry to inNodes. Unlike AlgaNodes, inNodes can here contain AlgaArgs, as the .replace
	//mechanism is difference. For AlgaNodes, AlgaArgs can only be used in the args initialization
	addInNodeAlgaNode { | sender, param = \in, mix = false |
		//Empty entry OR not doing mixing, create new IdentitySet. Otherwise, add to existing
		if((inNodes[param] == nil).or(mix.not), {
			inNodes[param] = IdentitySet[sender];
		}, {
			inNodes[param].add(sender);
		});

		//Update blocks too
		AlgaBlocksDict.createNewBlockIfNeeded(this, sender)
	}

	//Add entries to inNodes
	addInNodeListPattern { | sender, param = \in |
		sender.list.do({ | listEntry |
			case
			{ listEntry.isAlgaArg} {
				listEntry = listEntry.sender
			}
			{ listEntry.isAlgaNode } {
				if(inNodes.size == 0, {
					this.addInNodeAlgaNode(listEntry, param, mix:false);
				}, {
					this.addInNodeAlgaNode(listEntry, param, mix:true);
				});
			}
			{ listEntry.isListPattern } {
				this.addInNodeListPattern(listEntry)
			};
		});
	}

	//Wrapper for addInNode
	addInNode { | sender, param = \in, mix = false |
		//First of all, remove the outNodes that the previous sender had with the
		//param of this node, if there was any. Only apply if mix==false (no <<+ / >>+)
		if(mix == false, {
			var oldSenderSet = inNodes[param];
			if(oldSenderSet != nil, {
				oldSenderSet.do({ | oldSender |
					oldSender.outNodes.removeAt(this);
				});
			});
		});

		//If AlgaArg or ListPattern, loop around entries and add each of them
		if(sender.isAlgaNode, {
			this.addInNodeAlgaNode(sender, param, mix);
		}, {
			if(sender.isAlgaArg, {
				this.addInNode(sender.sender, param, mix);
			}, {
				if(sender.isListPattern, {
					this.addInNodeListPattern(sender, param);
				});
			});
		});

		//Use replaceArgs to set LATEST parameter, for retrieval after .replace ...
		//AlgaNode only uses this for number parameters, but AlgaPattern uses it for any
		//kind of parameter, including AlgaNodes and AlgaArgs.
		replaceArgs[param] = sender;
	}

	//Called from clearInner
	resetAlgaPattern {
		latestPatternInterpSumBusses.clear;
		currentActivePatternInterpSumBusses.clear;
		currentPatternBussesAndSynths.clear;
		currentActivePatternParamSynths.clear;
		currentActiveInterpBusses.clear;
		interpStreams = nil;
	}

	//Called from clearInner
	freeAllGroups { | now = false, time |
		this.freeAllSynths(false, now, time);
		super.freeAllGroups(now, time);
	}

	//There is no way to check individual synths.
	//So, let's at least check that the group must be insantiated
	algaInstantiated {
		if(algaCleared, { ^false });
		^(group.algaInstantiated);
	}

	//To send signal. algaInstantiatedAsReceiver is same as AlgaNode
	algaInstantiatedAsSender {
		if(algaCleared, { ^false });
		^((this.algaInstantiated).and(synthBus != nil));
	}

	isAlgaPattern { ^true }
}

//Alias
AP : AlgaPattern {}

//Implements Pmono behaviour
AlgaMonoPattern : AlgaPattern {}

//Alias
AMP : AlgaMonoPattern {}

//Extension to support out: from AlgaPattern
+AlgaNode {
	//Add a node to patternOutNodes
	addPatternOutNode { | algaPattern, param = \in |
		if(patternOutNodes == nil, { patternOutNodes = IdentityDictionary() });
		if(patternOutNodes[param] == nil, { patternOutNodes[param] = IdentitySet() });
		patternOutNodes[param].add(algaPattern);
	}

	//Remove a node from patternOutNodes
	removePatternOutNode { | algaPattern, param = \in |
		var patternOutNodesAtParam;
		if(patternOutNodes == nil, { ^this });
		patternOutNodesAtParam = patternOutNodes[param];
		if(patternOutNodesAtParam == nil, { ^this });
		patternOutNodesAtParam.remove(algaPattern);
	}

	//Used in AlgaBlock
	isContainedInPatternOut { | sender |
		if(patternOutNodes != nil, {
			patternOutNodes.do({ | algaNode |
				if(algaNode === sender, { ^true })
			})
		});
		^false
	}

	//Free previous out: connections from patterns (called in AlgaNode.replace)
	freeAllPatternOutConnections { | time |
		if(patternOutNodes != nil, {
			patternOutNodes.keysValuesDo({ | param, patternOutNodesAtParam |
				patternOutNodesAtParam.do({ | algaPattern |
					this.removePatternOutsAtParam(
						algaPattern: algaPattern,
						param: param,
						removePatternOutNodeFromDict: false,
						time: time
					);
				});
			});
		});
	}

	//Re-create previous out: connections with patterns (called in AlgaNode.replace)
	createAllPatternOutConnections { | time |
		if(patternOutNodes != nil, {
			patternOutNodes.keysValuesDo({ | param, patternOutNodesAtParam |
				patternOutNodesAtParam.do({ | algaPattern |
					scheduler.addAction(
						condition: { this.algaInstantiatedAsReceiver(param) },
						func: {
							this.receivePatternOutsAtParam(
								algaPattern: algaPattern,
								param: param,
								time: time
							);
						}
					)
				});
			});
		});
	}

	//Lock interpbus
	lockInterpBus { | uniqueID, interpBus |
		if(lockInterpBusses == nil, { lockInterpBusses = IdentityDictionary() });
		lockInterpBusses[uniqueID] = interpBus;
	}

	//Triggered when the connection is made
	receivePatternOutsAtParam { | algaPattern, param = \in, time = 0 |
		var controlNamesAtParam, paramRate, paramNumChannels;
		var patternOutEnvBussesAtParamAlgaPattern, patternOutEnvSynthsAtParamAlgaPattern;
		var patternOutUniqueIDsAtParam;
		var envBus, envSymbol, envSynth;
		var interpBusAtParam, interpBus;

		var algaSynthBus = algaPattern.synthBus;
		var uniqueID = UniqueID.next;
		var uniqueIDAlgaSynthBus = [uniqueID, algaSynthBus];
		var paramAlgaPatternAlgaSynthBus = [param, algaPattern, algaSynthBus];
		var paramAlgaPattern = [param, algaPattern];

		//Set time if needed
		time = time ? 0;

		//Get interpBus at param / sender combination
		interpBusAtParam = interpBusses[param];
		if(interpBusAtParam == nil, { ("AlgaNode: 'out': invalid interp bus at param '" ++ param ++ "'").error; ^this });

		//ALWAYS use the \default interpBus, which is connected to the \default normSynth.
		//Use the algaSynthBus as index, so that it can safely be removed in remove removePatternOutsAtParam.
		//Using algaPattern as index would not work on .replaces (it would remove it mid-replacement)
		interpBus = interpBusAtParam[\default];
		if(interpBus == nil, {
			(
				"AlgaNode: 'out': invalid interp bus at param '" ++
				param ++ "' and node " ++ algaPattern.asString
			).error;
			^this
		});

		//Check if patternOutUniqueIDs needs to be init.
		//Using dictionary to index with [param, algaPattern, algaSynthBus]
		if(patternOutUniqueIDs == nil, { patternOutUniqueIDs = Dictionary() });

		//Set of uniqueIDs at specific [param, algaPattern, algaSynthBus]
		if(patternOutUniqueIDs[paramAlgaPatternAlgaSynthBus] == nil, {
			patternOutUniqueIDs[paramAlgaPatternAlgaSynthBus] = IdentitySet()
		});

		//Add uniqueID to IdentitySet
		(patternOutUniqueIDs[paramAlgaPatternAlgaSynthBus]).add(uniqueID);

		//Check if patternOutEnvBusses needs to be init.
		//Using dictionary to index with [param, algaPattern]
		if(patternOutEnvBusses == nil, { patternOutEnvBusses = Dictionary() });

		//Check if patternOutEnvSynths needs to be init.
		//Using dictionary to index with [param, algaPattern]
		if(patternOutEnvSynths == nil, { patternOutEnvSynths = Dictionary() });

		//Check entries at [param, algaPattern]
		patternOutEnvBussesAtParamAlgaPattern = patternOutEnvBusses[paramAlgaPattern];
		patternOutEnvSynthsAtParamAlgaPattern = patternOutEnvSynths[paramAlgaPattern];

		//Create dict at [param, algaPattern].
		//Using dictionary to index with [uniqueID, algaSynthBus]
		if(patternOutEnvBussesAtParamAlgaPattern == nil, {
			patternOutEnvBusses[paramAlgaPattern] = Dictionary();
			patternOutEnvBussesAtParamAlgaPattern = patternOutEnvBusses[paramAlgaPattern]; //update pointer
		});

		//Create dict at [param, algaPattern].
		//Using dictionary to index with [uniqueID, algaSynthBus]
		if(patternOutEnvSynthsAtParamAlgaPattern == nil, {
			patternOutEnvSynths[paramAlgaPattern] = Dictionary();
			patternOutEnvSynthsAtParamAlgaPattern = patternOutEnvSynths[paramAlgaPattern]; //update pointer
		});

		//Get controlNames
		controlNamesAtParam = controlNames[param];
		if(controlNamesAtParam == nil, { ^this });

		//Get numChannels / rate of param
		paramNumChannels = controlNamesAtParam.numChannels;
		paramRate = controlNamesAtParam.rate;

		//Lock interpBus with uniqueID
		this.lockInterpBus(uniqueID, interpBus);

		//Create the envBus
		envBus = AlgaBus(server, 1, paramRate);

		//Add to patternOutEnvBusses
		patternOutEnvBussesAtParamAlgaPattern[uniqueIDAlgaSynthBus] = envBus;

		//envSymbol
		envSymbol = (
			"alga_patternOutEnv_" ++
			paramRate ++
			paramNumChannels
		).asSymbol;

		//envSynth:
		//This outputs both to interp bus in the form of [0, 0, 0, ..., env]
		//and both to envBus. envBus is then used as multiplier for the tempSynths later on.
		//The output to interpBus is fundamental in order for the envelope to be constant, and not
		//jittery across different triggering of synths (especially if overlapping)
		envSynth = AlgaSynth(
			envSymbol,
			[ \out, interpBus.index, \env_out, envBus.index, \fadeTime, time ],
			interpGroup,
			waitForInst:false
		);

		//Add to patternOutEnvSynths
		patternOutEnvSynthsAtParamAlgaPattern[uniqueIDAlgaSynthBus] = envSynth;

		//Update patternOutNodes
		this.addPatternOutNode(algaPattern, param);

		//Update blocks
		AlgaBlocksDict.createNewBlockIfNeeded(this, algaPattern)
	}

	//Trigger the release of all active out: synths at specific param for a specific algaPattern.
	//This is called everytime a connection is removed
	removePatternOutsAtParam { | algaPattern, param = \in, removePatternOutNodeFromDict = true, time |
		var paramAlgaPattern = [param, algaPattern];
		var patternOutEnvSynthsAtParamAlgaPattern = patternOutEnvSynths[paramAlgaPattern];
		var patternOutEnvBussesAtParamAlgaPattern = patternOutEnvBusses[paramAlgaPattern];

		//Set time if needed
		time = time ? 0;

		if(patternOutEnvSynthsAtParamAlgaPattern != nil, {
			patternOutEnvSynthsAtParamAlgaPattern.keysValuesDo({ | uniqueIDAlgaSynthBus, patternOutEnvSynth |
				var uniqueID = uniqueIDAlgaSynthBus[0];
				var algaSynthBus = uniqueIDAlgaSynthBus[1];
				var paramAlgaPatternUniqueID = [param, algaPattern, uniqueID];
				var paramAlgaPatternAlgaSynthBus = [param, algaPattern, algaSynthBus];

				var patternOutEnvBus = patternOutEnvBussesAtParamAlgaPattern[uniqueIDAlgaSynthBus];

				//Free bus and entries when synth is done.
				//It's still used while fade-out interpolation is happening
				patternOutEnvSynth.set(\t_release, 1, \fadeTime, time);
				patternOutEnvSynth.onFree({
					//Add the patternOutEnvBus to patternOutEnvBussesToBeFreed.
					//It must be a Dictionary cause of the Array key: needs to be checked by value...
					//This simply frees time of creating multiple IdentityDictionaries instead
					if(patternOutEnvBussesToBeFreed == nil, { patternOutEnvBussesToBeFreed = Dictionary() });

					//REVIEW THIS WITH THE NEW DICT MECHANISM: IT CREATES CLICKS AT TIMES!
					patternOutEnvBussesToBeFreed[uniqueIDAlgaSynthBus] = patternOutEnvBus;

					//Remove entries from patternOutEnvSynths and patternOutEnvBusses
					patternOutEnvSynths[paramAlgaPattern].removeAt(uniqueIDAlgaSynthBus);
					patternOutEnvBusses[paramAlgaPattern].removeAt(uniqueIDAlgaSynthBus);

					//Remove entries from lockInterpBusses
					if(lockInterpBusses != nil, { lockInterpBusses.removeAt(uniqueID) });

					//Remove uniqueID from patternOutUniqueIDs
					if(patternOutUniqueIDs != nil, {
						var patternOutUniqueIDsAtParamAlgaPattern = patternOutUniqueIDs[paramAlgaPatternAlgaSynthBus];
						if(patternOutUniqueIDsAtParamAlgaPattern != nil, {
							patternOutUniqueIDs[paramAlgaPatternAlgaSynthBus].remove(uniqueID)
						});
					});
				});
			});
		});

		//Update patternOutNodes only if needed. On .replace,this will be false.
		if(removePatternOutNodeFromDict, {
			this.removePatternOutNode(algaPattern, param);
		});
	}

	//Triggered every patternSynth. algaSynthBus is only used as a "indexer"
	receivePatternOutTempSynth { | algaPattern, algaSynthBus, outTempBus, algaNumChannels, algaRate,
		param = \in, patternBussesAndSynths, chans, scale |

		//Loop around the uniqueIDs for this specific [param, algaPattern] combo.
		//This allows for .replaces of receiver (this AlgaNode) to work.
		if(patternOutUniqueIDs != nil, {
			var paramAlgaPatternAlgaSynthBus = [param, algaPattern, algaSynthBus];
			var patternOutUniqueIDsAtParamAlgaPatternAlgaSynthBus = patternOutUniqueIDs[paramAlgaPatternAlgaSynthBus];
			if(patternOutUniqueIDsAtParamAlgaPatternAlgaSynthBus != nil, {
				patternOutUniqueIDsAtParamAlgaPatternAlgaSynthBus.do({ | uniqueID |
					var envBus;
					var controlNamesAtParam, paramNumChannels, paramRate;
					var interpBusAtParam, interpBus;
					var tempSynthSymbol;
					var tempSynthArgs, tempSynth;

					var uniqueIDAlgaSynthBus = [uniqueID, algaSynthBus];
					var paramAlgaPattern = [param, algaPattern];

					//Retrieve envBus from patternOutEnvBusses
					envBus = patternOutEnvBusses[paramAlgaPattern][uniqueIDAlgaSynthBus];
					if(envBus == nil, { ("AlgaNode: 'out': invalid envBus at param '" ++ param ++ "'").error; ^this });

					//Get controlNames
					controlNamesAtParam = controlNames[param];
					if(controlNamesAtParam == nil, { ^this });

					//Get channels / rate of param
					paramNumChannels = controlNamesAtParam.numChannels;
					paramRate = controlNamesAtParam.rate;

					//Calculate scale / chans
					scale = this.calculateScaling(
						param,
						nil,
						paramNumChannels,
						scale,
						false //don't update the AlgaNode's scalings dict
					);
					chans = this.calculateSenderChansMappingArray(
						param,
						nil,
						chans,
						algaNumChannels,
						paramNumChannels,
						false //don't update the AlgaNode's chans dict
					);

					//Get the locked interpBus (which is always \default, as it's the one connected to \default normSynth).
					//However, this will also work across .replace calls.
					interpBus = lockInterpBusses[uniqueID];
					if(interpBus == nil, { ("AlgaNode: 'out': invalid locked interp bus at param '" ++ param ++ "'").error; ^this });

					//Symbol. Don't use the fx version as \env is needed
					tempSynthSymbol = (
						"alga_pattern_" ++
						algaRate ++
						algaNumChannels ++
						"_" ++
						paramRate ++
						paramNumChannels ++
						"_out"
					).asSymbol;

					//Read from outTempBus and envBus, write to interpBus
					tempSynthArgs = [
						\in, outTempBus.busArg,
						\out, interpBus.index,
						\fadeTime, 0,
						\env, envBus.busArg
					];

					//Add scaling and chans (scale is an array already containing the symbol)
					tempSynthArgs = tempSynthArgs.addAll(scale).add(\indices).add(chans);

					//Create the tempSynth
					tempSynth = AlgaSynth(
						tempSynthSymbol,
						tempSynthArgs,
						interpGroup,
						\addToTail,
						waitForInst:false
					);

					//Add Synth to activeInterpSynthsAtParam
					this.addActiveInterpSynthOnFree(param, algaPattern, tempSynth);

					//Add Synth to patternBussesAndSynths
					patternBussesAndSynths.add(tempSynth);

					//Free dangling patternEnvBusses related to this [uniqueID, algaSynthBus] pair
					tempSynth.onFree({
						if(patternOutEnvBussesToBeFreed != nil, {
							var patternEnvBusAtUniqueID = patternOutEnvBussesToBeFreed[uniqueIDAlgaSynthBus];
							if(patternEnvBusAtUniqueID != nil, {
								patternOutEnvBussesToBeFreed.removeAt(uniqueIDAlgaSynthBus);
								//There still is the need of waiting a bit more, or it will click...
								//Just like freeUnusedInterpBusses: it's a syncing problem
								fork {
									1.wait;
									patternEnvBusAtUniqueID.free;
								};
							});
						});
					});
				});
			});
		});
	}
}