// Angular dev-server proxy. The backend port defaults to 18080 and can be overridden: API_PORT=18280 npm start
const port = process.env.API_PORT || process.env.SERVER_PORT || '18080';
const target = `http://localhost:${port}`;
const entry = { target, secure: false, changeOrigin: true };

module.exports = {
  '/api': entry,
  '/dev': entry,
  '/actuator': entry,
};
