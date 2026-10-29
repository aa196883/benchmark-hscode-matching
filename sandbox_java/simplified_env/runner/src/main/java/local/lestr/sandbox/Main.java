package local.lestr.sandbox;

/** Local command line for the merged RAG service. */
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "--help" : args[0];
        if (command.equals("--help")) {
            System.out.println("rag-import | rag DESCRIPTION [TOP_K [RETRIEVAL_K]] | rag-replay VECTOR_JSON RESPONSE_JSON DESCRIPTION [TOP_K [RETRIEVAL_K]]");
            return;
        }
        if (!command.equals("rag") && !command.equals("rag-replay") && !command.equals("rag-import"))
            throw new IllegalArgumentException("Unknown command; use --help");
        if (RagMain.run(args) != 0) System.exit(1);
    }
}
