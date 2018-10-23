/**
 * Copyright 2016 Signal K <info@signalk.org> and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

'use strict';

var utils = require('@signalk/nmea0183-utilities');

/*
50  Z2  XX  YY  YY  LAT position: XX degrees, (YYYY & 0x7FFF)/100 minutes
                     MSB of Y = YYYY & 0x8000 = South if set, North if cleared
                     Z= 0xA or 0x0 (reported for Raystar 120 GPS), meaning unknown
                     Stable filtered position, for raw data use command 58
                     Corresponding NMEA sentences: RMC, GAA, GLL
*/

module.exports = function (input) {
  var id = input.id,
      sentence = input.sentence,
      parts = input.parts,
      tags = input.tags;


  var Z = (parseInt(parts[1], 16) & 0xF0) >> 4;
  var XX = parseInt(parts[2], 16);
  var YYYY = parseInt(parts[3], 16) + 256 * parseInt(parts[4], 16);
  var s = 1;
  if ((YYYY & 0x8000) != 0) {
    s = -1;
  }
  var minutes = (YYYY & 0x7FFF) / 100.0;
  var latitude = s * (XX + minutes / 60);

  var pathValues = [];

  pathValues.push({
    path: 'navigation.position.latitude',
    value: utils.float(latitude)
  });

  return {
    updates: [{
      source: tags.source,
      timestamp: tags.timestamp,
      values: pathValues
    }]
  };
};