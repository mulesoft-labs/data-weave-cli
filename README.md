# DataWeave CLI

**DataWeave CLI** is a command-line interface that allows `querying`, `filtering`, and `mapping` structured data from different data sources like `JSON`, `XML`, `CSV`, `YML` to other data formats. It also allows to easily create data in such formats, all through the DataWeave language. For example:

`dw run 'output json --- { message: ["Hello", "world"] joinBy " "}'`

The DataWeave language is in the process of being open-sourced. You can read our announcement [here](https://blogs.mulesoft.com/news/dataweave/). Our journey has just begun and it will take some time for the code to be available. In the meantime, we want to start engaging with our community to understand how DataWeave could be used and integrated. 

If you are interested on leveraging DataWeave:
 1. Join our community [Slack](https://join.slack.com/t/dataweavelanguage/shared_invite/zt-1ewv2igp0-3ZiqQqaMdO_utwaEjxBpTw)
 2. Join the `#opensource` channel

For more news and all things DataWeave, visit our [site](https://dataweave.mulesoft.com/) 

## What is Included?

The binary distribution already ships with a set of modules and data formats that makes this useful for a very
interesting and broad set of use cases.

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

1. Download the latest [release version](https://github.com/mulesoft-labs/data-weave-cli/releases) according to your OS.
2. Unzip the file on your `<user.home>/.dw`
3. Add `<user.home>/.dw/bin` to your **PATH**

### Build and Install

To build the project, you need to run gradlew with the graalVM distribution based on Java 11. You can download it
at https://github.com/graalvm/graalvm-ce-builds/releases
Set:

```bash
export GRAALVM_HOME=`pwd`/.graalvm/graalvm-ce-java11-22.3.0/Contents/Home
export JAVA_HOME=`pwd`/.graalvm/graalvm-ce-java11-22.3.0/Contents/Home
```

Execute the gradle task `nativeCompile`

```bash
./gradlew native-cli:nativeCompile
```

It takes several minutes so good time to take and refill your mate.

Once it finishes you will find the `dw` binary in `native-cli/build/native/nativeCompile/dw`

## How to Use It

If the directory containing the `dw` executable is in your _PATH_, you can run `dw` from anywhere.

If it is not, go to the `bin` directory referenced in the installation instructions and run `dw` from there.

The following example shows the DataWeave CLI documentation

```bash
dw help
```

```bash
  ____   __  ____  __   _  _  ____   __   _  _  ____
(    \ / _\(_  _)/ _\ / )( \(  __) / _\ / )( \(  __)
 ) D (/    \ )( /    \\ /\ / ) _) /    \\ \/ / ) _)
(____/\_/\_/(__)\_/\_/(_/\_)(____)\_/\_/ \__/ (____)
Usage: <main class> [-hV] [COMMAND]
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  run            Runs provided DW script.
  wizard         Wizard actions.
    add            Adds a new Wizard to your network of trusted wizards.
  validate       Validate if a script is valid or not.
  spell          Runs the specified Spell.
    create         Creates a new spell with the given name.
    list           List all available spells.
    update         Update all spells to the latest one.
  help           Display help information about the specified command.
  repl           Starts the DW repl.
Example:

 dw  run -i payload <fullPathToUser.json> "output application/json --- payload
filter (item) -> item.age > 17"

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

## Dependency Manager

In order for a spell to depend on a library it can include a library it can use the dependencies.dwl to specify the list of dependencies that it should be included and download

```data-weave
%dw 2.0
var mavenRepositories = [{
    url: "https://maven.anypoint.mulesoft.com/api/v3/maven"
}]
---
{
  dependencies: [
    {
      kind: "maven",
      artifactId: "data-weave-analytics-library",
      groupId: "68ef9520-24e9-4cf2-b2f5-620025690913",
      version: "1.0.1",
      repositories: mavenRepositories // By default mulesoft, exchange and central are being added
    }
  ]
}
```

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
dw run -i payload=<fullpathToUsers.json> "output application/json --- payload filter (item) -> item.age > 17"
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
cat <fullpathToUser.json> | dw run "output application/json --- payload filter (item) -> item.age > 17"
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

### Using parameters
Using the internal map `params`, we can access injected parameters in the command line with the `-p` option
```
dw run -p myName=Julian "output json --- { name : params.myName }"
```
#### Output
```
{
  "name": "Julian"
}
```


## Contributions Welcome

Contributions to this project can be made through Pull Requests and Issues on the
[GitHub Repository](https://github.com/mulesoft-labs/data-weave-cli).

Before creating a pull request review the following:

* [LICENSE](LICENSE.txt)
* [SECURITY](SECURITY.md)
* [CODE_OF_CONDUCT](CODE_OF_CONDUCT.md)


When you submit your pull request, you are asked to sign a contributor license agreement (CLA) if we don't have one on file for you.
