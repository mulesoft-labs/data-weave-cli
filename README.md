# DataWeave CLI

**DataWeave CLI** allows executing queries and generate data directly from the command line.

**DataWeave CLI is** compiled with [Graal AOT](https://www.graalvm.org/docs/reference-manual/aot-compilation/) for fast bootstrap so download the one for your target OS.

## What is included?

The binary already ships with a set of modules and data formats that makes this useful for very interesting broad of use cases.

### Included Module
- [DataWeave Standar Libary](https://github.com/mulesoft/data-weave/tree/master/wlang)

### Included Formats

| Format    |             MimeType              |   Extension |
|-----------|:---------------------------------:|------------:|
| XML       |          application/xml          |        .xml |
| JSON      |         application/json          |       .json |
| CSV       |          application/csv          |        .csv |
| Properties |      text/x-java-properties       | .properties |
| TextPlain |            text/plain             |        .txt |
| Yaml      |         application/yaml          |       .yaml |
| Binary    |     application/octet-stream      |        .bin |
| Multipart |        multipart/form-data        |  .multipart |
| UrlEncoded | application/x-www-form-urlencoded | .urlencoded |
| NDjson    |       application/x-ndjson        |     .ndjson |

## How to install it? 

### Using Homebrew:

```
brew tap mulesoft-labs/data-weave
brew install dw
```

### Manually

1. Download the zip 
   1. [Mac](https://github.com/mulesoft-labs/data-weave-cli/releases/download/v1.0.16/dw-1.0.16-macOS)
   2. [Linux](https://github.com/mulesoft-labs/data-weave-cli/releases/download/v1.0.16/dw-1.0.16-Linux)
   3. [Windows](https://github.com/mulesoft-labs/data-weave-native/releases/download/v1.0.9/dw-1.0.9-Windows.zip) Very OLD :(!
2. Unzip the file on your `<home_directory>/.dw`
3. Add `<home_directory>/.dw/bin` to your PATH

### From code

Execute the gradle task nativeImage

```
./gradlew native-cli:nativeImage
```

It takes several minutes so good time to take and refill your mate.

Once it finishes you will find the `dw` binary in `native-cli/build/graal/dw`


## How to use it

If the directory containing the `dw` executable is in your PATH, you can run `dw` from anywhere. If it is not, go to the `bin` directory referenced in the installation instructions and run `dw` from there.
 

### Show documentation

`dw`

```bash
.........................................................................
.%%%%%....%%%%...%%%%%%...%%%%...%%...%%..%%%%%%...%%%%...%%..%%..%%%%%%.
.%%..%%..%%..%%....%%....%%..%%..%%...%%..%%......%%..%%..%%..%%..%%.....
.%%..%%..%%%%%%....%%....%%%%%%..%%.%.%%..%%%%....%%%%%%..%%..%%..%%%%...
.%%..%%..%%..%%....%%....%%..%%..%%%%%%%..%%......%%..%%...%%%%...%%.....
.%%%%%...%%..%%....%%....%%..%%...%%.%%...%%%%%%..%%..%%....%%....%%%%%%.
.........................................................................


Usage:

dw [-p <weavePath>]? [-i <name> <path>]* [-v]? [-o <outputPath>]? [[-f <filePath>] | <scriptContent>]

Arguments Detail:

 --prop or -p       | Property to be passed.
 --input or -i      | Declares a new input.
 --output or -o     | Specifies output file for the transformation if not standard output will be used.
 --file or -f       | Path to the file.
 --eval             | Evaluates the script instead of writing it.
 --version          | The version of the CLI and Runtime.
 --verbose or -v    | Enable Verbose Mode.
 --list-spells      | [Experimental] List all the available spells.
 --spell or -s      | [Experimental] Runs a spell. Use the <spellName> or <wizard>/<spellName> for spells from a given wizard.
 --local-spell      | [Experimental] Executes a local folder spell.
 --new-spell        | [Experimental] Create a new spell.
 --add-wizard       | [Experimental] Downloads wizard grimoire so that its spell are accessible.
 --remove-wizard    | [Experimental] Remove a wizard from your local repository.
 --update-grimoires | [Experimental] Update all wizard grimoires.


 Example:

 dw -i payload <fullpathToUser.json> "output application/json --- payload filter (item) -> item.age > 17"

 Documentation reference:

 https://docs.mulesoft.com/dataweave/latest/
```

### Query content from a file

Input file `users.json`

```json
[
  {
    "name": "User1",
    "age": 19
  },
  {
    "name": "User2",
    "age": 18
  },
  {
    "name": "User3",
    "age": 15
  },
  {
    "name": "User4",
    "age": 13
  },
  {
    "name": "User5",
    "age": 16
  }
]
```
 
Let's query users old enough to drink alcohol:

`dw -i payload <fullpathToUsers.json> "output application/json --- payload filter (item) -> item.age > 17"`


### Query Content From Standard Input

`cat <fullpathToUser.json> | dw "output application/json --- payload filter (item) -> item.age > 17"`


### Redirecting the Output to a File

`dw "output application/xml --- users: {( 1 to 100 map (item) -> {user: "User" ++ item} )}" >> out.xml`

### DataWeave Important Environment Variables

* *DW_HOME* = The directory where the home will be found if not defined `~/.dw` will be used
* *DW_LIB_PATH* = The directory where libraries are going to be search by default. If not defined `${DW_HOME}/libs` will be used
* *DW_DEFAULT_INPUT_MIMETYPE* = The default mimeType that is going to be used for the standard input. If not defined `application/json` will be used
* *DW_DEFAULT_OUTPUT_MIMETYPE* = The default output mimeType that is going to be if not defined. If not defined `application/json` will be used


## CURL + DataWeave CLI => Power API Playground

An interesting use case for the DataWeave CLI is to combine it with [curl](https://curl.haxx.se/)  


### Example Query GitHub Commits

We can use the GitHub API to query commits of a repo.

We can easily get the first commit by doing:

`curl "https://api.github.com/repos/mulesoft/mule/commits?per_page=5" | dw "payload[0]"`

or we can get the message by doing:

`curl "https://api.github.com/repos/mulesoft/mule/commits?per_page=5" | dw "{message: payload[0].commit.message}"` 


### HTTP POST Data Generated by DataWeave

This example will create a very big csv and stream it to the HTTP server on localhost.

`dw "output application/csv --- (1 to 10000000000000000000000) map (item) -> {name: 'User \$(item)'}" | curl -X POST  -T "/dev/stdin" http://localhost:8081/`


## Documentation
For more info about the language see the [docs site](https://docs.mulesoft.com/mule-runtime/latest/dataweave)

## Sharing Scripts

A spell is a very simple way to distribute and share a DataWeave transformation.

### Wizard

The first concept to learn is the `wizard` concept. A wizard is just a name that we trust and we want to have all their spells available.
A wizard has a `grimoire`, a GitHub repository that is inferred by `https://github.com/${wizard_name}/${wizard_name}-data-weave-grimoire`, that includes all the spells from this wizard.

By default there is a [default wizard](https://github.com/mulesoft-labs/data-weave-grimoire). 

### Adding a Wizard

In order to add a new wizard use

`dw --add-wizard <wizardName>`

The wizard grimoire is going to be cloned at `{user.home}/.dw/grimoires`

### Grimoire

A grimoire is a collection of `spells` from a given `wizard`. The default `grimoire` is located in [data-weave-grimoire](https://github.com/mulesoft-labs/data-weave-grimoire)
But it can also be consumed from other trusted `Wizard`. For example `leansh/Tutorial` is going to looks for a github repo called leansh-data-weave-grimoire under leansh user.

## Spells

Spells are just executables scripts that can be called from the command line using the `Spell` name.

### Running a Spell

Use `dw --spell <SpellName>`

#### Example: Running the Playground Locally

`dw --eval --spell Playground` 

It is going to execute the Playground spell that is going to be located in

`{user.home}/.dw/grimoires/data-weave-grimoire/Playground/src/Main.dwl`

### Listing all the available spells

Using the `--list-spells` it will show all the available spells for each wizard with the documentation of each spell. 

`dw --list-spells`

## Become a DataWeave wizard

### Step 1: Create the GitHub Repository

In order to become a wizard first create your GitHub repository

`https://github.com/${wizard_name}/${wizard_name}-data-weave-grimoire` 

Your wizard name is going to be your GitHub user id

### Step 2: Clone GitHub Repository

`git clone https://github.com/${wizard_name}/${wizard_name}-data-weave-grimoire`

### Step 3: Create a Spell

Inside your cloned repo run

`dw --new-spell <spellName>`

### Step 3: Edit Your Spell 

Using VSCode and with the vscode plugin

`code <spellName>`

### Step 4: Try it out

In order to test a local spell you can use 

`dw --local-spell ./<spellName>`

Or you if you want to use a live coding XP you can use the `--eval` flag that it will re-run the spell on every save

### Step 5: Push It and Distribute

Once your spell is finished push it to your repo and tell your friends to try it out.
