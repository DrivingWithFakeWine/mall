import Login from './pages/Login.tsx'
import {BrowserRouter, Routes, Route } from 'react-router-dom'
import Register from './pages/Register.tsx'
import Home from './pages/Home.tsx'

function App() {
  return (
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
            <Route path="/home" element={<Home />} />
        </Routes>
      </BrowserRouter>
  )
}

export default App

