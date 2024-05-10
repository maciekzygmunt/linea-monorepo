module.exports = {
  preset: "ts-jest",
  testEnvironment: "node",
  rootDir: ".",
  testRegex: "test.ts$",
  verbose: true,
  collectCoverage: true,
  collectCoverageFrom: ["src/**/*.ts"],
  coverageReporters: ["html", "lcov", "text"],
  testPathIgnorePatterns: [
    "src/clients/blockchain/typechain",
    "src/application/postman/persistence/migrations/",
    "src/application/postman/persistence/repositories/",
    "src/index.ts",
    "src/utils/WinstonLogger.ts",
  ],
  coveragePathIgnorePatterns: [
    "src/clients/blockchain/typechain",
    "src/application/postman/persistence/migrations/",
    "src/application/postman/persistence/repositories/",
    "src/index.ts",
    "src/utils/WinstonLogger.ts",
  ],
};
