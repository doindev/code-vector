// class CommentedLineClass {}
// function commentedLineFunc() {}
/* class CommentedBlockClass {
     method() {}
   }
   function commentedBlockFunc() {}
*/
import express from 'express';
// import secretImport from 'should-not-appear';

const app = express();
// app.get('/fake-commented-line', () => {});
/* app.post('/fake-commented-block', () => {}); */
app.get('/real-route', () => {});

export class RealTsClass {
    realTsMethod() { }
}

function realTsFunction() { }
