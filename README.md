# DataWeave CLI

**DataWeave CLI** allows executing queries and generate data directly from the command line.

**DataWeave CLI is** compiled with [Graal AOT](https://www.graalvm.org/docs/reference-manual/aot-compilation/) for fast bootstrap so download the one for your target OS.

## What is included?

The binary already ships with a set of modules and data-format that makes this useful for very interesting broad of use cases.

### Included Module
- [DataWeave Standard Library](https://github.com/mulesoft/data-weave/tree/master/wlang)

### Supported Data Formats

| MIME Type                           |      ID      |                                                                                             Supported Formats |
|-------------------------------------|:------------:|--------------------------------------------------------------------------------------------------------------:|
| `application/csv`                   |    `csv`     |                                [CSV Format](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-csv) |
| `application/json `                 |    `json`    |                              [JSON Format](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-json) |
| `application/octet-stream`          |   `binary`   |                          [Binary Format](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-binary) |
| `application/xml`                   |    `xml`     |                                [XML Format](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-xml) |
| `application/x-ndjson`              |   `ndjson`   | [Newline Delimited JSON Format (ndjson)](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-ndjson) |
| `application/x-www-form-urlencoded` | `urlencoded` |                 [URL Encoded Format](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-urlencoded) |
| `application/yaml`                  |    `yaml`    |                              [YAML Format](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-yaml) |
| `multipart/form-data`               | `multipart`  |                    [Multipart Format](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-multipart) |
| `text/plain`                        |    `text`    |                        [Text Plain Format](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-text) |
| `text/x-java-properties`            | `properties` |               [Text Java Properties](https://docs.mulesoft.com/dataweave/latest/dataweave-formats-properties) |

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
2. Unzip the file on your `<user.home>/.dw`
3. Add `<user.home>/.dw/bin` to your **PATH**

### From code

Execute the gradle task nativeImage

```
./gradlew native-cli:nativeImage
```

It takes several minutes so good time to take and refill your mate.

Once it finishes you will find the `dw` binary in `native-cli/build/graal/dw`


## How to use it

If the directory containing the `dw` executable is in your **PATH**, you can run `dw` from anywhere. If it is not, go to the `bin` directory referenced in the installation instructions and run `dw` from there.
 

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

### Query Content From a File

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

### DataWeave CLI Environment Variables

* *DW_HOME* = The directory where the home will be found if not defined `~/.dw` will be used
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
