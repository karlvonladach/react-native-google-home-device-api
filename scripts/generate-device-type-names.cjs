const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const [typesJar, homeJar] = process.argv.slice(2);
if (!typesJar || !homeJar) {
  throw new Error(
    'Usage: node scripts/generate-device-type-names.cjs <types-classes.jar> <home-classes.jar>'
  );
}

const entries = execFileSync('jar', ['tf', typesJar], {
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024,
});
const jarEntries = entries.split(/\r?\n/);
const classes = jarEntries
  .filter((entry) =>
    /^com\/google\/home\/(?:matter\/standard|google)\/[^/$]+Device\.class$/.test(
      entry
    )
  )
  .map((entry) => entry.replace(/\.class$/, '').replace(/\//g, '.'));
if (classes.length === 0) {
  throw new Error('No built-in device classes found in the SDK');
}

const output = execFileSync(
  'javap',
  [
    '-constants',
    '-classpath',
    [homeJar, typesJar].join(path.delimiter),
    ...classes,
  ],
  { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 }
);
const matches = Array.from(
  output.matchAll(
    /public (?:final |abstract )?class ([\w.]+) extends com\.google\.home\.DeviceType \{[^}]*?public static final java\.lang\.String TYPE_ID = "([^"]+)";/g
  )
);
if (matches.length !== classes.length) {
  throw new Error(
    `Expected ${classes.length} device IDs, found ${matches.length}`
  );
}

const names = new Map();
for (const [, className, id] of matches) {
  const name = className
    .split('.')
    .pop()
    .replace(/^Google/, '')
    .replace(/Device$/, '')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/\bOn Off\b/g, 'On/Off');
  if (!name || names.has(id)) {
    throw new Error(`Missing or duplicate device type name for ${id}`);
  }
  names.set(id, name);
}

const lines = [...names.entries()]
  .sort(([a], [b]) => a.localeCompare(b))
  .map(([id, name]) => `  "${id}" to "${name}",`);
const file = path.join(
  __dirname,
  '..',
  'android',
  'src',
  'main',
  'java',
  'com',
  'googlehomedeviceapi',
  'DeviceTypeNames.kt'
);
fs.writeFileSync(
  file,
  `package com.googlehomedeviceapi\n\n` +
    `// Generated from the Google Home SDK; see scripts/generate-device-type-names.cjs.\n` +
    `internal val deviceTypeNames = mapOf(\n${lines.join('\n')}\n)\n`
);

const entrySet = new Set(jarEntries);
const traits = jarEntries
  .filter((entry) =>
    /^com\/google\/home\/(?:matter\/standard|google)\/[^/$]+Trait\.class$/.test(
      entry
    )
  )
  .map((entry) => entry.replace(/Trait\.class$/, '.class'));
for (const trait of traits) {
  if (
    !entrySet.has(trait) ||
    !entrySet.has(trait.replace(/\.class$/, '$Companion.class'))
  ) {
    throw new Error(`Missing trait factory: ${trait}`);
  }
}
if (traits.length === 0) throw new Error('No built-in trait factories found');
const factoryPath = path.join(path.dirname(file), 'SdkFactories.kt');
const namesFor = (values) =>
  values
    .map((value) => value.replace(/\.class$/, '').replace(/\//g, '.'))
    .sort()
    .map((value) => `    ${value},`)
    .join('\n');
const deviceClasses = classes.map(
  (value) => value.replace(/\./g, '/') + '.class'
);
fs.writeFileSync(
  factoryPath,
  `package com.googlehomedeviceapi\n\n` +
    `// Generated from the Google Home SDK; see scripts/generate-device-type-names.cjs.\n` +
    `internal object SdkFactories {\n` +
    `  val types = listOf(\n${namesFor(deviceClasses)}\n  )\n` +
    `  val traits = listOf(\n${namesFor(traits)}\n  )\n}\n`
);

const commandClasses = jarEntries.filter((entry) =>
  /^com\/google\/home\/(?:matter\/standard|google)\/[^/$]+Trait\$[^/$]+Command\.class$/.test(
    entry
  )
);
if (commandClasses.length === 0) {
  throw new Error('No built-in command descriptors found');
}
const commands = commandClasses
  .map((entry) => {
    const [trait, command] = entry.replace(/\.class$/, '').split('$');
    const factory = trait.replace(/Trait$/, '').replace(/\//g, '.');
    if (!entrySet.has(trait.replace(/Trait$/, '.class'))) {
      throw new Error(`Missing command trait factory: ${entry}`);
    }
    return `    ${factory}.factory.traitId to ${trait.replace(/\//g, '.')}.${command},`;
  })
  .sort();
fs.writeFileSync(
  path.join(path.dirname(file), 'SdkCommands.kt'),
  `package com.googlehomedeviceapi\n\n` +
    `// Generated from the Google Home SDK; see scripts/generate-device-type-names.cjs.\n` +
    `internal val sdkCommands = listOf(\n${commands.join('\n')}\n)\n`
);
