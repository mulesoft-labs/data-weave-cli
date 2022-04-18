# DataWeave CLI

**DataWeave CLI** is a command-line interface that allows `querying`, `filtering`, and `mapping` structured data from different data sources like `JSON`, `XML`, `CSV`, `YML` to other data formats. It also allows to easily create data in such formats.

For more info about the `DataWeave` language visit the [documenation site](https://docs.mulesoft.com/mule-runtime/latest/dataweave)

## What is Included?
The binary distribution already ships with a set of modules and data formats that makes this useful for a very interesting and broad set of use cases.

### Included Modules
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

## Installation 

### Homebrew (Mac)

```bash
brew tap mulesoft-labs/data-weave
brew install dw
```

### Manual Installation
1. Download the zip 
   1. [Mac](https://github.com/mulesoft-labs/data-weave-cli/releases/download/v1.0.16/dw-1.0.16-macOS)
   2. [Linux](https://github.com/mulesoft-labs/data-weave-cli/releases/download/v1.0.16/dw-1.0.16-Linux)
   3. [Windows](https://github.com/mulesoft-labs/data-weave-native/releases/download/v1.0.9/dw-1.0.9-Windows.zip) Very OLD :(!
2. Unzip the file on your `<user.home>/.dw`
3. Add `<user.home>/.dw/bin` to your **PATH**

### Build and Install
Execute the gradle task `nativeImage`

```bash
./gradlew native-cli:nativeImage
```

It takes several minutes so good time to take and refill your mate.

Once it finishes you will find the `dw` binary in `native-cli/build/graal/dw`

## How to Use It

If the directory containing the `dw` executable is in your _PATH_, you can run `dw` from anywhere. 

If it is not, go to the `bin` directory referenced in the installation instructions and run `dw` from there.

The following example shows the DataWeave CLI documentation 
```bash
dw --help
```

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
 --update-grimoires | [Experimental] Update all wizard grimoires.


 Example:

 dw -i payload <fullpathToUser.json> "output application/json --- payload filter (item) -> item.age > 17"

 Documentation reference:

 https://docs.mulesoft.com/dataweave/latest/
```

### DataWeave CLI Environment Variables

The following are the DataWeave CLI environment variables that you can set in your operating system:

| Environment Variable         | Description                                                                                                             |
|------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `DW_HOME`                    | The directory where the home will be found if not defined `~/.dw` will be used.                                         |
| `DW_DEFAULT_INPUT_MIMETYPE`  | The default `mimeType` that is going to be used for the standard input. If not defined `application/json` will be used. |
| `DW_DEFAULT_OUTPUT_MIMETYPE` | The default output `mimeType` that is going to be if not defined. If not defined `application/json` will be used.       |


### Querying Content From a File

Giving the following input file `users.json`

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

```bash
dw -i payload <fullpathToUsers.json> "output application/json --- payload filter (item) -> item.age > 17"
```

#### Output

```json
[
  {
    "name": "User1",
    "age": 19
  },
  {
    "name": "User2",
    "age": 18
  }
]
```

### Query Content From Standard Input

```bash
cat <fullpathToUser.json> | dw "output application/json --- payload filter (item) -> item.age > 17"
```


### Redirecting the Output to a File

```bash 
dw "output application/xml --- users: {( 1 to 100 map (item) -> {user: "User" ++ item} )}" >> out.xml
```

## CURL + DataWeave => Power API Playground

An interesting use case for the DataWeave CLI is to combine it with [curl](https://curl.haxx.se/)

### Query a GET Response

We can use the GitHub API to query commits of a repository.

We can easily get the first commit by doing:

```bash
curl "https://api.github.com/repos/mulesoft/mule/commits?per_page=5" | dw "payload[0]"
```

or we can get the message by doing:

```bash 
curl "https://api.github.com/repos/mulesoft/mule/commits?per_page=5" | dw "{message: payload[0].commit.message}"
``` 

### Generate a Request with Body
This example uses the [jsonplaceholder API](https://jsonplaceholder.typicode.com/) to update a resource.

Steps:
1. Search the post resource with the `id = 1`.
2. Use DataWeave CLI to create a JSON output changing the post title `My new title`.
3. Finally, update the post resource.

```bash
curl https://jsonplaceholder.typicode.com/posts/1 | dw "output application/json --- { id: payload.id, title: 'My new title', body: payload.body, userId: payload.userId }" | curl -X PUT -H "Content-type: application/json; charset=UTF-8" -T "/dev/stdin" https://jsonplaceholder.typicode.com/posts/1 -v
```

#### Output
```json
{
  "id": 1,
  "title": "My new title",
  "body": "quia et suscipit\nsuscipit recusandae consequuntur expedita et cum\nreprehenderit molestiae ut ut quas totam\nnostrum rerum est autem sunt rem eveniet architecto",
  "userId": 1
}
```