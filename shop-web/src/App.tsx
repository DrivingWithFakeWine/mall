import Login from './pages/Login.tsx'
import {BrowserRouter, Routes, Route } from 'react-router-dom'
import Register from './pages/Register.tsx'
import Home from './pages/Home.tsx'
import ProductDetailPage from './pages/ProductDetail.tsx'

function App() {
  return (
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/home" element={<Home />} />
          <Route path="/product/:id" element={<ProductDetailPage />} />
        </Routes>
      </BrowserRouter>
  )
}

export default App
